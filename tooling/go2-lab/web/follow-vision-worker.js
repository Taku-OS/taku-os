/* Local inference only. This worker never talks to the robot. */
importScripts('/vision-assets/vision_bundle.js');
let model, canvas, context, detectPeople;
self.onmessage=async ({data})=>{
  try {
    if(data.type==='init') {
      const files=await Vision.FilesetResolver.forVisionTasks('/vision-assets');
      detectPeople=(await import('/follow-person.js')).peopleFromLandmarks;
      model=await Vision.PoseLandmarker.createFromOptions(files,{
        baseOptions:{modelAssetPath:'/vision-assets/pose_landmarker_lite.task',delegate:'CPU'},
        runningMode:'VIDEO',numPoses:3,minPoseDetectionConfidence:.35,
        minPosePresenceConfidence:.35,minTrackingConfidence:.4,
      });
      self.postMessage({type:'ready'});return;
    }
    if(data.type!=='frame'||!model)return;
    const start=performance.now(), image=data.image;
    try {
      if(!canvas) {canvas=new OffscreenCanvas(image.width,image.height);context=canvas.getContext('2d',{willReadFrequently:true});}
      if(canvas.width!==image.width||canvas.height!==image.height){canvas.width=image.width;canvas.height=image.height;}
      context.drawImage(image,0,0);
      const result=model.detectForVideo(canvas,data.timestamp);
      const pixels=context.getImageData(0,0,canvas.width,canvas.height);
      const people=detectPeople(result.landmarks,pixels.data,canvas.width,canvas.height);
      self.postMessage({type:'result',id:data.id,landmarks:result.landmarks,people,elapsedMs:performance.now()-start});
    } finally {image.close();}
  }catch(error){self.postMessage({type:'error',error:String(error.message||error)});}
};
