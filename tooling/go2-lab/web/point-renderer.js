// Full point buffers are uploaded once per sensor frame; rotation stays in shaders.
window.Go2PointRenderer = class {
  constructor(canvas) {
    this.canvas = canvas;
    this.gl = canvas.getContext('webgl', {antialias:false, alpha:false, depth:true, preserveDrawingBuffer:false});
    this.points = new Float32Array();
    this.meta = null;
    this.drawCalls = 0;
    this.drawnPoints = 0;
    this.error = null;
    if (!this.gl) { this.error = 'WEBGL_UNAVAILABLE'; return; }
    this.initialize();
    canvas.addEventListener('webglcontextlost', event => { event.preventDefault(); this.error='WEBGL_CONTEXT_LOST'; });
    canvas.addEventListener('webglcontextrestored', () => {
      try { this.initialize(); this.upload(this.points, this.meta); }
      catch { this.error='WEBGL_RESTORE_FAILED'; }
    });
  }
  initialize() {
    const gl=this.gl;
    function shader(type,source) {
      const value=gl.createShader(type);gl.shaderSource(value,source);gl.compileShader(value);
      if (!gl.getShaderParameter(value,gl.COMPILE_STATUS)) throw new Error('SHADER_COMPILE_FAILED');
      return value;
    }
    const vertex=shader(gl.VERTEX_SHADER, `
      attribute vec3 position;
      uniform vec3 center;
      uniform vec2 rotation, viewport, zRange;
      uniform float span, pointSize, live;
      varying vec3 color;
      void main() {
        vec3 p=position-center;
        float a=p.x*cos(rotation.x)-p.y*sin(rotation.x);
        float b=p.x*sin(rotation.x)+p.y*cos(rotation.x);
        float y=b*sin(rotation.y)-p.z*cos(rotation.y);
        float depth=b*cos(rotation.y)+p.z*sin(rotation.y);
        float scale=min(viewport.x,viewport.y)*0.64/max(span,1.0);
        gl_Position=vec4(a*scale*2.0/viewport.x,-y*scale*2.0/viewport.y,-depth/(max(span,1.0)*2.0),1.0);
        gl_PointSize=pointSize;
        float h=clamp((position.z-zRange.x)/max(zRange.y-zRange.x,0.01),0.0,1.0);
        vec3 tint=mix(vec3(0.24,0.67,0.60),vec3(0.91,0.91,0.42),h);
        color=mix(vec3(0.078,0.145,0.122),tint,mix(0.28,1.0,live));
      }`);
    const fragment=shader(gl.FRAGMENT_SHADER, `precision mediump float;varying vec3 color;void main(){gl_FragColor=vec4(color,1.0);}`);
    this.program=gl.createProgram();gl.attachShader(this.program,vertex);gl.attachShader(this.program,fragment);gl.linkProgram(this.program);
    gl.deleteShader(vertex);gl.deleteShader(fragment);
    if(!gl.getProgramParameter(this.program,gl.LINK_STATUS))throw new Error('SHADER_LINK_FAILED');
    this.buffer=gl.createBuffer();
    this.position=gl.getAttribLocation(this.program,'position');
    this.uniforms=Object.fromEntries(['center','rotation','viewport','zRange','span','pointSize','live'].map(name=>[name,gl.getUniformLocation(this.program,name)]));
    gl.enable(gl.DEPTH_TEST);gl.depthFunc(gl.LEQUAL);gl.clearColor(.078,.145,.122,1);
    this.error=null;
  }
  upload(points,meta) {
    if(!(points instanceof Float32Array) || points.length%3 || points.length/3!==(meta?.pointCount||0))throw new Error('POINT_COUNT_MISMATCH');
    this.points=points;this.meta=meta;
    if(!this.gl || this.error)return;
    this.gl.bindBuffer(this.gl.ARRAY_BUFFER,this.buffer);
    this.gl.bufferData(this.gl.ARRAY_BUFFER,points,this.gl.DYNAMIC_DRAW);
  }
  draw(yaw,pitch,live) {
    if(!this.gl||this.error)return false;
    const gl=this.gl,canvas=this.canvas,ratio=Math.min(devicePixelRatio||1,2);
    const width=canvas.clientWidth,height=canvas.clientHeight;
    if(!width||!height)return false;
    const w=Math.round(width*ratio),h=Math.round(height*ratio);
    if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}
    gl.viewport(0,0,w,h);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
    this.drawnPoints=0;
    if(!this.points.length||!this.meta)return true;
    gl.useProgram(this.program);gl.bindBuffer(gl.ARRAY_BUFFER,this.buffer);
    gl.enableVertexAttribArray(this.position);gl.vertexAttribPointer(this.position,3,gl.FLOAT,false,0,0);
    gl.uniform3fv(this.uniforms.center,this.meta.center);
    gl.uniform2f(this.uniforms.rotation,yaw,pitch);
    gl.uniform2f(this.uniforms.viewport,width,height);
    gl.uniform2fv(this.uniforms.zRange,this.meta.zRange);
    gl.uniform1f(this.uniforms.span,this.meta.span);
    gl.uniform1f(this.uniforms.pointSize,1.3*ratio);
    gl.uniform1f(this.uniforms.live,live?1:0);
    gl.drawArrays(gl.POINTS,0,this.points.length/3);
    this.drawnPoints=this.points.length/3;this.drawCalls++;
    return true;
  }
};
