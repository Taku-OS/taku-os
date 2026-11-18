package org.agentos.assistant;

import android.content.Context;
import android.graphics.Typeface;
import android.text.method.LinkMovementMethod;
import android.view.*;
import android.widget.*;
import org.commonmark.node.Node;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import java.util.*;

/** Shared column widths and stable cells, inside a horizontal viewport on narrow screens. */
final class NativeMarkdownTable extends HorizontalScrollView {
    private final TableLayout table;
    private final AgentSettingsUi ui;
    private final DrawerTheme theme;
    private final List<android.widget.TableRow> rows = new ArrayList<>();

    NativeMarkdownTable(Context context, DrawerTheme theme) {
        super(context);
        this.theme = theme; ui = new AgentSettingsUi(context, theme);
        setFillViewport(true); setFocusable(false);
        setContentDescription("Table; scroll horizontally");
        table = new TableLayout(context);
        table.setBackground(theme.rounded(context, theme.raised, 10, true));
        table.setPadding(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1));
        table.setClipToOutline(true);
        addView(table, new LayoutParams(-2, -2));
    }

    void bind(Node node) {
        int index = 0;
        for (Node section = node.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                android.widget.TableRow rowView;
                if (index < rows.size()) rowView = rows.get(index);
                else {
                    rowView = new android.widget.TableRow(getContext()); rows.add(rowView);
                    table.addView(rowView);
                    View divider = new View(getContext()); divider.setBackgroundColor(theme.border);
                    table.addView(divider, new TableLayout.LayoutParams(-1, ui.dp(1)));
                }
                boolean header = section instanceof TableHead;
                rowView.setBackgroundColor(header ? theme.bubble : theme.raised);
                int column = 0;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    TextView text;
                    if (column < rowView.getChildCount()) text = (TextView) rowView.getChildAt(column);
                    else {
                        text = ui.text("", 14, true);
                        text.setTextIsSelectable(true);
                        text.setLinkTextColor(theme.accent);
                        text.setMovementMethod(LinkMovementMethod.getInstance());
                        text.setMaxWidth(ui.dp(240)); text.setMinWidth(ui.dp(80));
                        text.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
                        rowView.addView(text);
                    }
                    String signature = header + NativeMarkdown.signature(cell);
                    if (!signature.equals(text.getTag())) {
                        text.setText(NativeMarkdown.render(cell, theme.bubble, ui.dp(10)));
                        text.setTypeface(Typeface.DEFAULT, header ? Typeface.BOLD : Typeface.NORMAL);
                        TableCell.Alignment align = ((TableCell) cell).getAlignment();
                        text.setGravity(align == TableCell.Alignment.RIGHT ? Gravity.RIGHT
                                : align == TableCell.Alignment.CENTER ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
                        text.setTag(signature);
                    }
                    column++;
                }
                while (rowView.getChildCount() > column) rowView.removeViewAt(rowView.getChildCount() - 1);
                index++;
            }
        }
        while (rows.size() > index) {
            rows.remove(rows.size() - 1);
            table.removeViews(table.getChildCount() - 2, 2);
        }
    }
}
