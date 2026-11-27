package org.agentos.memory;

import java.util.*;
import java.util.regex.*;

/**
 * Which languages memory's local rules understand, and which language a piece of text is in.
 *
 * A language is supported only when Privacy has sensitive-topic rules for it: SUPPORTED is Privacy.RULES' key set, so adding
 * a language starts with its privacy rules. Proactive and Recall keep their own per-language tables (dates, times,
 * requests, priorities) and StoreTests checks that those cover exactly the supported languages.
 *
 * Text in a language the privacy rules cannot read fails closed: Privacy treats it as sensitive, so it stays out of the user
 * card, the agenda lists and the context a reminder is written from. It is still stored and searchable, like everything else.
 *
 * Detection is by script, and for Latin script by telltale words and letters, since English rules cannot read Spanish or
 * German. Han without kana is Chinese; kana means Japanese. Digits, punctuation and symbols belong to no language.
 */
final class Lang {
 static final Set<String> SUPPORTED=Privacy.RULES.keySet();
 static final Pattern LATIN_WORD=Pattern.compile("\\p{IsLatin}[\\p{IsLatin}'’]*");
 // Common words that are not English words (or are rare in English text), per language. Two of them mark the text.
 // Common words that are not English words (or are rare in English text), per language. Two of them, or one in a text with
 // no English function word, mark the text as that language.
 static final Map<String,Set<String>> LATIN_WORDS=Map.of(
  "es",Set.of("el","la","los","las","de","del","al","que","por","para","con","una","está","estás","es","muy","pero","como","cómo","qué","mañana","hoy","gracias","hola","sí","también","porque","mi","tu","su","sus","yo","nos","tiene","tengo","tienes","siempre","nosotros","ustedes","ayer","cuando","dónde"),
  "fr",Set.of("le","la","les","de","des","du","au","aux","et","est","une","pour","avec","dans","pas","ne","que","qui","je","il","elle","nous","vous","mon","ma","sur","demain","aujourd'hui","merci","bonjour","oui","très","mais","ce","cette","sont","avez","j'ai"),
  "de",Set.of("der","die","das","und","ist","nicht","ein","eine","mit","für","auf","ich","du","wir","ihr","sie","mein","meine","dein","am","im","zu","von","bei","nach","kommt","morgen","heute","danke","bitte","ja","nein","auch","aber","wie","wo","bin","bist","sind","haben"),
  "pt",Set.of("de","da","do","os","um","uma","não","com","em","no","na","para","você","meu","minha","obrigado","obrigada","amanhã","hoje","sim","também","mas","muito","eu","nós","está","são","tem","isso","esse","essa"),
  "it",Set.of("il","la","gli","de","del","della","che","è","di","per","non","una","con","sono","mio","mia","domani","oggi","grazie","ciao","anche","ma","molto","io","noi","voi","questo","questa"),
  "nl",Set.of("de","het","een","en","van","niet","met","ik","jij","wij","morgen","vandaag","dank","bedankt","ook","maar","zijn","heb","hebt","wat","waar"),
  "id",Set.of("yang","dan","ini","itu","untuk","dengan","tidak","saya","kamu","kami","besok","hari","terima","kasih","juga","ada","sudah","akan","bisa"));
 static final Set<String> ENGLISH=Set.of("the","a","an","and","or","to","of","in","on","at","for","with","is","are","was","were","be","been","it","this","that","you","your","i","me","my","we","our","he","she","they","them","his","her","their","do","does","did","have","has","had","will","can","could","would","should","not","no","yes","what","when","where","who","how","why","from","by","about","tomorrow","today","tonight","please","thanks","let's","lets","just","so","if","but","up","out","get","got");
 // Accented letters English borrows (café, naïve, façade, über); any other accented Latin letter, or ¿ ¡, points elsewhere.
 static final String ENGLISH_ACCENTS="éèêëïîôûçàâäöü";

 /** The languages a text is written in: "zh", "en", another code, or "other" for a script without rules. */
 static Set<String> of(String s){
  Set<String> out=new TreeSet<>();if(s==null||s.isEmpty())return out;
  boolean han=false,kana=false,latin=false;
  for(int i=0;i<s.length();){
   int cp=s.codePointAt(i);i+=Character.charCount(cp);
   if(!Character.isLetter(cp))continue;
   Character.UnicodeScript sc=Character.UnicodeScript.of(cp);
   switch(sc){
    case HAN:han=true;break;
    case HIRAGANA:case KATAKANA:kana=true;break;
    case LATIN:latin=true;break;
    case COMMON:case INHERITED:break;   // e.g. the prolonged sound mark, fullwidth forms counted elsewhere
    case HANGUL:out.add("ko");break;
    default:out.add(sc.name().toLowerCase(Locale.ROOT));
   }
  }
  if(kana)out.add("ja");else if(han)out.add("zh");
  if(latin)out.add(latin(s));
  return out;
 }
 /** English unless another Latin-script language has more telltale words (two, or one with no English word beside it), or accents English does not use. */
 static String latin(String s){
  Map<String,Integer> votes=new HashMap<>();int english=0;
  Matcher m=LATIN_WORD.matcher(s);
  while(m.find()){String w=m.group().toLowerCase(Locale.ROOT).replace('’','\'');if(ENGLISH.contains(w))english++;
   for(Map.Entry<String,Set<String>> e:LATIN_WORDS.entrySet())if(e.getValue().contains(w))votes.merge(e.getKey(),1,Integer::sum);}
  int letters=0;
  for(int i=0;i<s.length();){int cp=s.codePointAt(i);i+=Character.charCount(cp);
   if(cp=='¿'||cp=='¡')letters++;
   else if(cp>0x7F&&Character.isLetter(cp)&&Character.UnicodeScript.of(cp)==Character.UnicodeScript.LATIN&&cp<0xFF00&&ENGLISH_ACCENTS.indexOf(Character.toLowerCase(cp))<0)letters++;}
  String best=null;int top=0;for(Map.Entry<String,Integer> e:votes.entrySet())if(e.getValue()>top){top=e.getValue();best=e.getKey();}
  if(best!=null&&top>english&&(top>=2||letters>=2||english==0))return best;
  if(letters>=2&&english==0)return best!=null?best:"latin";
  return "en";
 }
 /** Whether the privacy rules can read every language in the text. Text with no letters is covered. */
 static boolean covered(String s){return SUPPORTED.containsAll(of(s));}
}
