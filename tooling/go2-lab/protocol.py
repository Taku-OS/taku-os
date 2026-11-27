"""The complete outbound allowlist for the read-only Go2 integration."""
import math
POSE_TOPIC = 'rt/utlidar/robot_pose'

TOPICS = {'rt/lf/lowstate', 'rt/lf/sportmodestate'}
LIDAR_TOPIC = 'rt/utlidar/voxel_map_compressed'
STALE_SECONDS = 5


def permitted(message):
    kind, topic = message.get('type'), message.get('topic', '')
    if kind in {'subscribe', 'unsubscribe'}:
        return topic in TOPICS | {LIDAR_TOPIC, POSE_TOPIC} and set(message) == {'type','topic'}
    if kind == 'msg' and topic == 'rt/utlidar/switch':
        return message.get('data') == 'on'
    if kind == 'rtc_inner_req' and topic == '' and message.get('data') == {
            'req_type': 'disable_traffic_saving', 'instruction': 'on'}:
        return True
    if kind == 'req' and topic == 'rt/api/motion_switcher/request':
        data = message.get('data', {})
        if not isinstance(data, dict): return False
        header = data.get('header', {})
        if not isinstance(header, dict): return False
        identity = header.get('identity', {}) if isinstance(header, dict) else {}
        return (set(message) == {'type','topic','data'} and set(data) == {'header','parameter'}
                and set(header) == {'identity'} and isinstance(identity, dict)
                and set(identity) == {'id','api_id'} and type(identity['id']) is int
                and type(identity['api_id']) is int and identity['api_id'] == 1001 and data['parameter'] == '')
    if topic != '':
        return False
    if kind == 'validation':
        return isinstance(message.get('data'), str)
    data = message.get('data')
    if kind == 'heartbeat':
        return isinstance(data, dict) and set(data) == {'timeInStr', 'timeInNum'}
    return (kind == 'rtc_inner_req' and isinstance(data, dict)
            and data.get('req_type') in {'public_network_status', 'rtt_probe_send_from_mechine'})


def number(value):
    return value if type(value) in (int, float) and math.isfinite(value) else None


def telemetry(topic, data):
    if not isinstance(data, dict):
        return {}
    if topic == 'rt/lf/lowstate':
        bms = data.get('bms_state', {})
        soc = number(bms.get('soc')) if isinstance(bms, dict) else None
        return {'batteryPercent': soc if soc is not None and 0 <= soc <= 100 else None,
                'voltage': number(data.get('power_v'))}
    if topic == 'rt/lf/sportmodestate':
        from perception import sport_pose
        velocity = data.get('velocity')
        return {'bodyHeight': number(data.get('body_height')), 'mode': number(data.get('mode')),
                'velocity': [number(v) for v in velocity] if isinstance(velocity, list) and len(velocity) == 3 else None,
                'yawSpeed': number(data.get('yaw_speed')), 'pose': sport_pose(data)}
    return {}


def sample_view(sample, now, connected):
    if sample is None:
        return {'fresh': False, 'ageMs': None, 'values': None, 'count': 0}
    age = max(0, now - sample['received'])
    return {'fresh': connected and age <= STALE_SECONDS, 'ageMs': round(age * 1000),
            'values': sample['values'], 'count': sample['count']}
