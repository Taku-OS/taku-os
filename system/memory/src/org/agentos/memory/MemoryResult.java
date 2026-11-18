package org.agentos.memory;

import android.text.Html;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.URLSpan;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Readable local Agent output. Raw HTML stays text; no images, links, or network side effects. */
final class MemoryResult {
 private static final Parser PARSER=Parser.builder().build();
 private static final HtmlRenderer HTML=HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build();
 static CharSequence render(String text){
  SpannableStringBuilder result=new SpannableStringBuilder(Html.fromHtml(HTML.render(PARSER.parse(text)),Html.FROM_HTML_MODE_COMPACT));
  // Result text is selectable; following model-generated links requires leaving this view explicitly.
  for(URLSpan link:result.getSpans(0,result.length(),URLSpan.class))result.removeSpan(link);
  return result;
 }
}
