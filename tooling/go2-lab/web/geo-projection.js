// GCJ-02 lon/lat projected onto a fixed AMap static-map viewport.
(function(root){
 const world=p=>[(p[0]+180)/360,(1-Math.asinh(Math.tan(p[1]*Math.PI/180))/Math.PI)/2];
 const geo=p=>[p[0]*360-180,Math.atan(Math.sinh(Math.PI*(1-2*p[1])))*180/Math.PI];
 // AMap Web Service staticmaps scale=1: measured 139.8 px per .003° at zoom 15.
 // This zoom convention differs by one level from 256-pixel slippy-map tiles.
 const pixel=(p,v)=>{const a=world(p),c=world(v.center),s=512*2**v.zoom;return [(a[0]-c[0])*s+v.width/2,(a[1]-c[1])*s+v.height/2];};
 const coordinate=(p,v)=>{const c=world(v.center),s=512*2**v.zoom;return geo([c[0]+(p[0]-v.width/2)/s,c[1]+(p[1]-v.height/2)/s]);};
 root.go2GeoProjection={pixel,coordinate};
 if(typeof module!=='undefined')module.exports=root.go2GeoProjection;
})(typeof window!=='undefined'?window:globalThis);
