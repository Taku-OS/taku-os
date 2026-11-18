import React from 'react';
import {PluginMarketScreen} from '../components/PluginMarketScreen';
import {createNativePluginRepository} from '../plugins/nativeRepository';
import {callAgent} from '../native/agent';

export default function PluginMarketRoute() {
  return <PluginMarketScreen repository={createNativePluginRepository((action, params) => callAgent(action, params))}/>;
}
