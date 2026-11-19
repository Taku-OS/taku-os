import React, {useState} from 'react';
import {AppPermissionsScreen} from '../components/AppPermissionsScreen';
import {createNativeAppPermissionsRepository} from '../model/appPermissionsRepository';
import {callAgent} from '../native/agent';

export default function AppPermissionsRoute() {
  const [repository] = useState(() => createNativeAppPermissionsRepository((action, params) => callAgent(action, params)));
  return <AppPermissionsScreen repository={repository}/>;
}
