package org.taku.go2lab;

import java.util.Locale;

/** Presentation intent/state only. This class has no transport or model access. */
final class EncounterScript {
  String phase="", request="";
  String pending="", pendingRequest="", pendingStage="";
  private int revision;
  boolean manual;
  boolean active(){return !phase.isEmpty();}
  boolean processing(){return !pending.isEmpty();}
  static String intent(String text){
    String value=text.trim().toLowerCase(Locale.ROOT).replaceAll("[.!。！]+$","");
    if(value.matches("go handle him for me|go handle it|go get him|去会会他|帮我去会会他|去找他"))return "go";
    if(value.matches("come back|come back to me|return|回来|回到我身边"))return "return";
    if(value.matches("say it|say the line|talking to you\\? i'm enough|play the line|说台词|播放台词"))return "say";
    if(value.matches("stay|stay here|hold|hold position|停留|停在这里"))return "stay";
    if(value.matches("circle|circle around|circle around him|环绕|绕着他走"))return "circle";
    if(value.matches("bark|dog bark|狗叫|叫两声"))return "bark";
    if(value.equals("confirm"))return "confirm";
    if(value.matches("stop audio|停止音频"))return "audio_stop";
    if(value.matches("all set|finish|done|结束任务|完成"))return "complete";
    return "";
  }
  boolean accept(String text){
    String intent=intent(text);
    if(intent.equals("go")){
      if(!active()||phase.equals("complete")){phase="confirm";request="Go handle him for me.";manual=false;}
      return true;
    }
    if(!active())return false;
    boolean inProgress=!phase.equals("confirm")&&!phase.equals("complete");
    if(intent.equals("stay")&&inProgress){
      phase=phase.equals("returning")||phase.equals("return_hold")?"return_hold":"holding";
      request="Stay here.";return true;
    }
    if(intent.equals("circle")&&(phase.equals("running")||phase.equals("holding")||phase.equals("circling"))&&!manual){
      phase="circling";request="Circle around him.";return true;
    }
    if(intent.equals("return")&&inProgress&&!manual){
      phase="returning";request="Come back.";return true;
    }
    if(intent.equals("complete")){phase="complete";manual=false;return true;}
    return false;
  }
  void confirm(){if(phase.equals("confirm")&&!manual)phase="running";}
  int queue(String text){
    String action=intent(text);
    boolean immediate=action.equals("stay")||action.equals("complete");
    if(processing()&&!immediate||manual&&!immediate)return 0;
    EncounterScript probe=new EncounterScript();probe.phase=phase;probe.manual=manual;
    boolean allowed=action.equals("confirm")?phase.equals("confirm"):
      action.equals("say")||action.equals("bark")?active()&&!phase.equals("confirm"):probe.accept(text);
    if(!allowed)return 0;
    cancelPending();
    // Stop/hold the current presentation immediately; only the acknowledgement is delayed.
    if(immediate||action.equals("go"))accept(text);
    pending=action;
    if(action.equals("go")||action.equals("return"))pendingStage="locating";
    pendingRequest=switch(action){
      case "go","confirm" -> "Go handle him for me.";
      case "return" -> "Come back.";
      case "circle" -> "Circle around him.";
      case "stay" -> "Stay here.";
      case "say" -> "Say it.";
      case "bark" -> "Bark.";
      case "complete" -> "All set.";
      default -> text;
    };
    return revision;
  }
  int delayMillis(){return switch(pending){case "go","return"->6000;case "circle"->1500;case "confirm","say","bark"->1100;default->800;};}
  boolean advancePlanning(int ticket){
    if(ticket!=revision||!pendingStage.equals("locating"))return false;
    pendingStage="planning";return true;
  }
  String settle(int ticket){
    if(ticket!=revision||!processing())return "";
    String action=pending,text=pendingRequest;cancelPending();
    if(action.equals("confirm"))confirm();
    else if(action.equals("say")||action.equals("bark"))request=text;
    else {accept(text);if(action.equals("go"))confirm();}
    return action;
  }
  void cancelPending(){revision++;pending="";pendingRequest="";pendingStage="";}
  void clear(){cancelPending();phase="";request="";manual=false;}
}
