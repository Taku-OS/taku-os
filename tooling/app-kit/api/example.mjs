// Apps define arbitrary methods and business logic; this is a blank API module.
export default function createApp({store,notify,error}){
  return{title:'My application',version:'1',methods:{
    // 'your.method': {description:'...', paramsSchema:{type:'object',...},
    //   handler(params){ /* actual business code */ return ...; }}
  }};
}
