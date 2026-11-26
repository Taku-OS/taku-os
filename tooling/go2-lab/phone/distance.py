"""Relative odometry movement using existing acknowledged, interruptible pulses.

This is not obstacle avoidance or calibrated external position measurement.
The app requires the operator to confirm the entire travel path before starting.
"""
import asyncio
import math


def valid_distance(action):
    return (isinstance(action, dict) and set(action) == {'action', 'direction', 'distance_m'}
            and action['action'] == 'move_distance' and isinstance(action['direction'], str)
            and action['direction'] in {'forward', 'backward'}
            and type(action['distance_m']) in (int, float) and math.isfinite(action['distance_m'])
            and .05 <= action['distance_m'] <= 1.)


def valid_turn(action):
    return (isinstance(action, dict) and set(action) == {'action', 'direction', 'angle_deg'}
            and action['action'] == 'turn_angle' and isinstance(action['direction'], str)
            and action['direction'] in {'left', 'right'}
            and type(action['angle_deg']) in (int, float) and math.isfinite(action['angle_deg'])
            and 5 <= action['angle_deg'] <= 180)


async def pose(bridge):
    state = await bridge.snapshot()
    sample = state.get('telemetry', {}).get('rt/lf/sportmodestate', {})
    values = sample.get('values') or {}; value = values.get('pose') or {}
    position = value.get('position'); yaw = value.get('yaw')
    if (not state.get('connected') or not sample.get('fresh')
            or type(sample.get('ageMs')) not in (int, float) or not 0 <= sample['ageMs'] <= 400
            or not isinstance(position, (list, tuple)) or len(position) != 3
            or any(type(n) not in (int, float) or not math.isfinite(n) for n in [*position, yaw])):
        raise ValueError('DISTANCE_POSE_UNAVAILABLE')
    return position[0], position[1], yaw


def displacement(start, current, direction, target):
    dx, dy = current[0]-start[0], current[1]-start[1]
    heading = start[2]; sign = 1 if direction == 'forward' else -1
    progress = sign*(dx*math.cos(heading)+dy*math.sin(heading))
    lateral = abs(-dx*math.sin(heading)+dy*math.cos(heading))
    yaw_error = abs(math.atan2(math.sin(current[2]-heading), math.cos(current[2]-heading)))
    if yaw_error > math.radians(15) or lateral > .10: raise ValueError('DISTANCE_PATH_DRIFT')
    if progress < -.05: raise ValueError('DISTANCE_WRONG_DIRECTION')
    if progress > target+.10: raise ValueError('DISTANCE_OVERSHOOT')
    return progress


async def move_distance(assistant, action, job):
    bridge = assistant.bridge; turning = action['action'] == 'turn_angle'; direction = action['direction']
    target = math.radians(action['angle_deg']) if turning else action['distance_m']
    tolerance = math.radians(2) if turning else .015
    # Verify position before any mode change or movement.
    await pose(bridge)
    await assistant.ensure_normal(job)
    start = await pose(bridge)
    previous = bridge.motion.snapshot()
    speeds = {'linearSpeed': previous['linearSpeed'], 'angularSpeed': previous['angularSpeed']}
    configured = await bridge.motion_speed({**speeds, **({'angularSpeed': .3} if turning else {'linearSpeed': .12})})
    if configured.get('requestError'): raise ValueError(configured['requestError'])
    progress = 0.; pulses = 0; stalled = 0; last = start; rotation = 0.
    def observe(current):
        nonlocal last, rotation
        if math.hypot(current[0]-last[0], current[1]-last[1]) > .12:
            raise ValueError('DISTANCE_POSE_JUMP')
        delta = math.atan2(math.sin(current[2]-last[2]), math.cos(current[2]-last[2]))
        if abs(delta) > .2: raise ValueError('DISTANCE_POSE_JUMP')
        rotation += delta; last = current
        if not turning: return displacement(start, current, direction, target)
        value = rotation * (1 if direction == 'left' else -1)
        if math.hypot(current[0]-start[0], current[1]-start[1]) > .15: raise ValueError('DISTANCE_PATH_DRIFT')
        if value < -math.radians(5): raise ValueError('DISTANCE_WRONG_DIRECTION')
        if value > target+math.radians(10): raise ValueError('DISTANCE_OVERSHOOT')
        return value
    def report():
        job['progress'] = ({'kind':'angle', 'targetDegrees':round(math.degrees(target),1), 'turnedDegrees':round(math.degrees(progress),1), 'pulses':pulses}
                           if turning else {'kind':'distance', 'targetM':target, 'traveledM':round(progress,3), 'pulses':pulses})
    try:
        while pulses < 40:
            assistant.authorized(job)
            while bridge.motion.snapshot()['cooldownMs']:
                assistant.authorized(job); observe(await pose(bridge)); await asyncio.sleep(.08)
            current = await pose(bridge)
            progress = observe(current); report()
            if progress >= target-tolerance: break
            assistant.authorized(job)
            armed = await bridge.motion_command('arm')
            if not armed.get('armed'): raise ValueError(armed.get('error') or 'STATE_NOT_READY')
            assistant.authorized(job)
            before = progress; pulse = asyncio.create_task(bridge.motion_command(direction, sustain=True)); problem = None
            try:
                while not pulse.done():
                    assistant.authorized(job)
                    current = await pose(bridge)
                    progress = observe(current); report()
                    if progress >= target-tolerance: bridge.motion.revoke()
                    await asyncio.sleep(.04)
            except (Exception, asyncio.CancelledError) as error:
                problem = error; bridge.motion.revoke()
            finally:
                # Revoke and await the pulse's acknowledged StopMove before returning.
                bridge.motion.revoke()
                result = await pulse
                bridge.release_expired_manual()
            if result.get('error') == 'STOP_UNCONFIRMED': raise ValueError('STOP_UNCONFIRMED')
            if problem: raise problem
            if result.get('error') or result.get('result') != 'stop_acknowledged':
                raise ValueError(result.get('error') or 'STOP_UNCONFIRMED')
            pulses += 1
            progress = observe(await pose(bridge))
            stalled = stalled+1 if progress-before < .005 else 0
            if stalled >= 3: raise ValueError('DISTANCE_NO_PROGRESS')
        else: raise ValueError('DISTANCE_LIMIT')
        stopped = await bridge.motion_command('stop')
        if stopped.get('error') or stopped.get('result') != 'stop_acknowledged': raise ValueError('STOP_UNCONFIRMED')
        return {**({'targetDegrees':round(math.degrees(target),1), 'turnedDegrees':round(math.degrees(progress),1)} if turning else {'targetM':target,'traveledM':round(progress,3)}), 'pulses': pulses,
                'measurement': 'robot-relative-odometry', 'source': 'simulation' if bridge.simulated else 'real',
                'result': 'stop_acknowledged'}
    finally:
        bridge.motion.revoke(); bridge.release_expired_manual()
        await bridge.motion_speed(speeds)
