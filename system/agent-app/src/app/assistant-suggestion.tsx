import React from 'react';
import {useLocalSearchParams} from 'expo-router';
import {AssistantSuggestionScreen} from '../components/AssistantSuggestionScreen';

export default function AssistantSuggestionRoute() {
  const {captureId} = useLocalSearchParams<{captureId?: string | string[]}>();
  return <AssistantSuggestionScreen captureId={typeof captureId === 'string' ? captureId : ''}/>;
}
