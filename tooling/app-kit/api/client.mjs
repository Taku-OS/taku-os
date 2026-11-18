import http from 'node:http';
const [command,app,method,params,...options]=process.argv.slice(2);
const base='/v1/apps';let url,body;
if(command==='status')url='/health';
else if(command==='list')url=base;
else if(command==='describe'&&app)url=base+'/'+encodeURIComponent(app);
else if(command==='openapi')url='/v1/openapi.json';
else if(command==='call'&&app&&method){url=base+'/'+encodeURIComponent(app)+'/call';try{body={method,params:params?JSON.parse(params):{}};const index=options.indexOf('--key');if(index>=0){if(!options[index+1])throw Error('Missing key');body.idempotencyKey=options[index+1];}}catch(e){console.error(JSON.stringify({ok:false,error:{code:'INVALID_CLI_ARGS',message:e.message}}));process.exit(2);}}
else{console.error('mini-app status | list | describe APP | call APP METHOD JSON [--key KEY] | openapi');process.exit(2);}
const req=http.request({host:'127.0.0.1',port:Number(process.env.MINI_APP_API_PORT||8765),path:url,method:body?'POST':'GET',headers:body?{'Content-Type':'application/json'}:{}},res=>{let output='';res.setEncoding('utf8');res.on('data',s=>output+=s);res.on('end',()=>{try{console.log(JSON.stringify(JSON.parse(output),null,2));}catch{console.error('Invalid server response');process.exitCode=1;}if(res.statusCode>=400)process.exitCode=1;});});
req.setTimeout(10000,()=>req.destroy(new Error('Application API timed out')));
req.on('error',e=>{console.error(JSON.stringify({ok:false,error:{code:'API_UNAVAILABLE',message:e.message}}));process.exitCode=1;});
req.end(body?JSON.stringify(body):undefined);
