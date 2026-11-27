package android.icu.text;
/** JVM stand-in for Android's ICU: delegates to ICU4J. */
public final class BreakIterator{
 public static final int DONE=com.ibm.icu.text.BreakIterator.DONE;final com.ibm.icu.text.BreakIterator b;
 BreakIterator(com.ibm.icu.text.BreakIterator b){this.b=b;}
 public static BreakIterator getWordInstance(android.icu.util.ULocale l){return new BreakIterator(com.ibm.icu.text.BreakIterator.getWordInstance(l.icu()));}
 public void setText(String s){b.setText(s);}public int first(){return b.first();}public int next(){return b.next();}
}
