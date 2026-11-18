import {Platform} from 'react-native';
import {File} from 'expo-file-system';
import {ImageManipulator, SaveFormat, type ImageManipulatorContext, type ImageRef} from 'expo-image-manipulator';
import type {Language} from '../i18n';
import {analyzeCameraFrame} from '../native/agent';
import {updateCamera, useMediaStore} from './genuiMediaStore';

/** Analyze the current original still once, fencing results against a newer frame or explicit goal. */
export async function analyzeCameraPhoto(language: Language) {
  const session = useMediaStore.getState().camera;
  const {photo, analysisPrompt: instruction, analysisRequest} = session;
  if (!photo || session.analyzing || session.busy) return;
  updateCamera({analyzing: true, analysisError: '', analyzedRequest: analysisRequest});
  const zh = language === 'zh';
  let frameFile: File | undefined;
  let context: ImageManipulatorContext | undefined;
  let rendered: ImageRef | undefined;
  try {
    context = ImageManipulator.manipulate(photo.uri);
    context.resize(photo.width >= photo.height ? {width: Math.min(640, photo.width)} : {height: Math.min(640, photo.height)});
    rendered = await context.renderAsync();
    const frame = await rendered.saveAsync({format: SaveFormat.JPEG, compress: 0.5, base64: true});
    if (Platform.OS !== 'web') frameFile = new File(frame.uri);
    const data = `data:image/jpeg;base64,${frame.base64 ?? ''}`;
    if (!frame.base64 || data.length > 300000) throw new Error(zh ? '本次画面过大，无法提交给图片模型。' : 'This frame exceeds the image model input limit.');
    const analysis = await analyzeCameraFrame({image: data, language, ...(instruction ? {instruction} : {})});
    const current = useMediaStore.getState().camera;
    if (current.photo?.uri === photo.uri && current.analysisRequest === analysisRequest && current.analysisPrompt === instruction)
      updateCamera({analysis});
  } catch (error) {
    const current = useMediaStore.getState().camera;
    if (current.photo?.uri === photo.uri && current.analysisRequest === analysisRequest && current.analysisPrompt === instruction)
      updateCamera({analysisError: `${zh ? '构图分析需要已授权且已连接的系统图片模型：' : 'Composition analysis requires an authorized, connected system image model: '}${error instanceof Error ? error.message : String(error)}`});
  } finally {
    rendered?.release(); context?.release();
    if (frameFile?.exists && frameFile.uri !== photo.uri) frameFile.delete();
    updateCamera({analyzing: false});
  }
}
