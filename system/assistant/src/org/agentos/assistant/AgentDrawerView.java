package org.agentos.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import org.json.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** System-sheet UI; delegates data and lifecycle to a controller and repository. */
final class AgentDrawerView extends FrameLayout implements AgentDrawerController.Listener {
    interface Host {
        default void sensitive(boolean value) {}

        void dismiss();

        void openMirror();

        String foreground();

        android.graphics.Bitmap screenshot();
        default String pageText() { return ""; }
        default void suggestScreen() {}
    }

    private final Host host;
    private final AgentDrawerController controller;
    private DrawerTheme theme;
    private LinearLayout sheet, messages, composerArea;
    private ScrollView scroll;
    private EditText composer;
    private TextView title, notice;
    private ImageButton send, stop;
    private ImageButton headerNavigation, headerSettings, headerHistory, headerNew, headerClose;
    private NativeConversationView transcript;
    private ChatWorkspacePreview workspacePreview;
    private LinearLayout timelineHost, queuedHost;
    private String timelineSignature = "", queueSignature = "";
    private String page = "chat", activeTask = "", draftThread = "", renderedRequestKeys = "";
    private String nativeReturnPage = "settings";
    private AgentConversation conversation;
    private JSONObject snapshot = new JSONObject();
    private boolean busy, followResponse;
    private String pendingHomeId = "", pendingHomeText = "", pendingHomeThread = "";
    private boolean pendingHomeNew;
    private String renderedThread = "";
    private int navBottom, imeBottom;
    private int availableSheetHeight;
    private float sheetFraction = 1f;
    private AgentSettingsView preferencesView;
    private AgentAccountView accountView;
    private AgentSkillsView skillsView;
    private AgentHarnessView harnessView;
    private AgentNativeView nativeView;
    private ComposerToolbar composerToolbar;
    private LinearLayout attachmentPreview, jevCard;
    private Button jevAnalyze, jevAccept;
    private TextView jevStatus;
    private JevScreenDecision jevDecision;
    private JevScreenDecision pendingAcceptedJev;
    private boolean jevPending, jevSubmitting;
    private int jevGeneration;
    private String jevRequest = "";
    private final java.util.Map<String, ComposerDraft> pendingSubmissions = new java.util.HashMap<>();
    private boolean destroyed;
    private String savedComposerText = "";
    private String keyboardTask = "", keyboardDraftRevision = "";
    private boolean keyboardHandled, keyboardDismissed;
    private boolean composerChinese;
    private final java.util.Map<String, ReadingPosition> readingPositions = new java.util.HashMap<>();
    private String restoreReadingThread = "";
    private ReadingPosition layoutReadingPosition;
    private String layoutReadingThread = "";
    private int readingInteractionVersion;

    private static final class ReadingPosition {
        String key;
        int offset, scroll, selectionStart, selectionEnd;
        boolean follow;
    }

    /** A saved reading anchor always wins over incidental layout growth. */
    static boolean followContentGrowth(boolean following, boolean hasSavedAnchor,
            int scrollY, int viewportHeight, int previousContentHeight, int bottomTolerance) {
        return !hasSavedAnchor && (following || viewportHeight > 0
                && scrollY + viewportHeight >= previousContentHeight - bottomTolerance);
    }

    private void rememberReadingPosition() {
        if (!page.equals("chat") || transcript == null || scroll == null || draftThread.isEmpty()
                || transcript.getParent() != messages) return;
        ReadingPosition saved = new ReadingPosition();
        View anchor = transcript.scrollAnchor(scroll.getScrollY());
        saved.key = transcript.anchorKey(anchor);
        saved.offset = anchor == null ? 0 : scroll.getScrollY() - transcript.getTop() - anchor.getTop();
        saved.scroll = scroll.getScrollY();
        saved.follow = saved.scroll + scroll.getHeight() >= messages.getHeight() - dp(24);
        saved.selectionStart = composer.getSelectionStart();
        saved.selectionEnd = composer.getSelectionEnd();
        readingPositions.put(draftThread, saved);
    }

    private void openWorkspace(JSONObject lease) {
        rememberReadingPosition();
        saveDraft();
        final String openingThread = controller.threadId();
        if (WorkspaceFloatService.show(getContext(), lease, () -> {
            if (openingThread.equals(controller.threadId()) && isShown()) host.dismiss();
        })) {
            return;
        }
        android.content.Intent intent = new android.content.Intent(getContext(), MirrorActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("returnConversationId", controller.threadId());
        for (String key : new String[]{"threadId", "screenKey", "generation"})
            intent.putExtra(key, lease.optString(key));
        getContext().startActivity(intent);
        host.dismiss();
    }

    AgentDrawerView(Context context, Host host) {
        super(context);
        WorkspaceFloatService.hide(context);
        this.host = host;
        controller = new AgentDrawerController(context, this);
        setBackgroundColor(context.getColor(R.color.taku_overlay));
        setOnClickListener(view -> host.dismiss());
        setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                    imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    positionSheet();
                    return insets;
                });
        build();
    }

    private int dp(float value) {
        return theme.dp(getContext(), value);
    }

    private AgentRepository repository() {
        return controller.repository();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(5), 1);
        return view;
    }

    private LinearLayout.LayoutParams spaced(int width, int height, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(margin);
        return params;
    }

    private ImageButton icon(int resource, String label, Runnable action, boolean filled) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(resource);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(filled ? theme.raised : theme.ink));
        button.setContentDescription(label);
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        button.setBackground(
                theme.rounded(getContext(), filled ? theme.ink : Color.TRANSPARENT, 24, false));
        button.setOnClickListener(view -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private Button action(String label, Runnable callback) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextColor(theme.ink);
        ChatDesign.text(button, ChatDesign.Role.LABEL);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    private void build() {
        keyboardTask = "";
        if (workspacePreview != null) workspacePreview.stop();
        stopPreferencePages();
        theme =
                new DrawerTheme(getContext(),
                        !repository().preferences().getString("theme", "light").equals("light"));
        removeAllViews();
        sheet = column();
        sheet.setOnClickListener(view -> {});
        GradientDrawable background = theme.rounded(getContext(), theme.surface, 24, false);
        background.setCornerRadii(new float[] {dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        sheet.setBackground(background);
        sheet.setClipToOutline(true);
        sheet.setPadding(dp(16), 0, dp(16), dp(16));
        FrameLayout.LayoutParams sheetBounds = new FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM);
        // Local PRD-to-Android sheet mapping, not a DS spacing token: the outer
        // host inset is separate from the single 16dp content gutter above.
        sheetBounds.setMargins(dp(8), 0, dp(8), 0);
        addView(sheet, sheetBounds);

        FrameLayout handle = new FrameLayout(getContext());
        handle.setContentDescription(getContext().getString(R.string.chat_dismiss));
        handle.setFocusable(true);
        handle.setOnClickListener(view -> {
            sheetFraction = sheetFraction < .9f ? 1f : .65f;
            positionSheet();
        });
        View pill = new View(getContext());
        pill.setBackground(theme.rounded(getContext(), theme.muted, 3, false));
        FrameLayout.LayoutParams pillParams =
                new FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER);
        handle.addView(pill, pillParams);
        sheet.addView(handle, new LinearLayout.LayoutParams(-1, dp(24)));
        // Only the handle owns resizing. Scrolling the thread never drags the host.
        handle.setOnTouchListener(new View.OnTouchListener() {
            float startY, startHeight, overflow;
            public boolean onTouch(View view, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        sheet.animate().cancel();
                        startY = event.getRawY(); startHeight = sheet.getHeight(); overflow = 0;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float wanted = startHeight - (event.getRawY() - startY);
                        int minimum = Math.min(availableSheetHeight, dp(320));
                        overflow = Math.max(0, minimum - wanted);
                        float size = Math.max(minimum, Math.min(availableSheetHeight, wanted));
                        sheetFraction = availableSheetHeight == 0 ? 1 : size / availableSheetHeight;
                        sheet.setTranslationY(overflow);
                        positionSheet();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (overflow > dp(96)) host.dismiss();
                        else if (Math.abs(event.getRawY() - startY) < dp(4)) view.performClick();
                        sheet.setTranslationY(0);
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        sheet.setTranslationY(0); return true;
                    default: return false;
                }
            }
        });

        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        headerNavigation = icon(R.drawable.ic_taku_chevron_left, getContext().getString(R.string.chat_history), () -> {
            if (page.equals("skills")) { if (skillsView == null || !skillsView.back()) settings(); }
            else if (page.equals("harness")) settings();
            else if (page.equals("native")) { if (nativeView == null || !nativeView.back()) returnFromNative(); }
            else if (page.equals("settings") || page.equals("sessions")) { stopPreferencePages(); page="chat"; render(); if(conversation!=null)setConversationTitle(conversation); }
            else if (page.equals("account")) settings();
            else sessions();
        }, false);
        header.addView(headerNavigation);
        title = text(getContext().getString(R.string.chat_new), 18, theme.ink);
        ChatDesign.text(title, ChatDesign.Role.CONTEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setContentDescription(getContext().getString(R.string.chat_current_title));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        headerSettings = icon(R.drawable.ic_taku_more, getContext().getString(R.string.chat_more), () -> {
            if (page.equals("chat")) chatOptions();
            else if (page.equals("skills") && skillsView != null) skillsView.options(headerSettings);
            else if (page.equals("harness") && harnessView != null) harnessView.options(headerSettings);
            else if (page.equals("native") && nativeView != null) nativeView.options(headerSettings);
            else settings();
        }, false);
        headerSettings.setBackgroundColor(Color.TRANSPARENT);
        headerNavigation.setBackgroundColor(Color.TRANSPARENT);
        header.addView(headerSettings);
        headerHistory = icon(R.drawable.ic_taku_history,
                getContext().getString(R.string.chat_history), this::sessions, false);
        header.addView(headerHistory);
        headerNew = icon(R.drawable.ic_taku_plus,
                getContext().getString(R.string.chat_new), this::newConversation, false);
        header.addView(headerNew);
        headerClose = icon(R.drawable.ic_taku_close, getContext().getString(R.string.chat_close), host::dismiss, false);
        header.addView(headerClose);
        sheet.addView(header);
        View divider = new View(getContext());
        divider.setBackgroundColor(theme.border);
        sheet.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        scroll = new ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                followResponse = false;
                layoutReadingPosition = null;
                restoreReadingThread = "";
                readingInteractionVersion++;
            }
            return false;
        });
        messages = column();
        messages.setPadding(0, dp(16), 0, dp(16));
        messages.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t == ob - ot && r - l == or - ol) return;
            final String layoutThread = renderedThread;
            final int interaction = readingInteractionVersion;
            boolean savedAnchor = layoutReadingPosition != null && layoutThread.equals(layoutReadingThread);
            final boolean followGrowth = followContentGrowth(followResponse, savedAnchor,
                    scroll.getScrollY(), scroll.getHeight(), ob - ot, dp(24));
            // The preview arrives asynchronously after the first transcript layout.
            // Keep an existing reading anchor; otherwise preserve an actual bottom
            // follower when the appended preview increases the content height.
            post(() -> {
                if (view != messages || !page.equals("chat") || transcript == null || transcript.getParent() != messages
                        || !layoutThread.equals(renderedThread) || interaction != readingInteractionVersion
                        || !isShown() || getWindowVisibility() != VISIBLE) return;
                JSONObject nativeState = snapshot.optJSONObject("native");
                JSONArray questions = nativeState == null ? null : nativeState.optJSONArray("requests");
                // Preserve the existing pending-question placement, including its form.
                if (questions != null && questions.length() > 0) return;
                if (layoutReadingPosition != null && renderedThread.equals(layoutReadingThread)) {
                    restoreScroll(layoutReadingPosition, transcript);
                } else if (followGrowth) {
                    followResponse = true;
                    scroll.scrollTo(0, Math.max(0, messages.getHeight() - scroll.getHeight()));
                }
            });
        });
        scroll.addView(messages);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        notice = text("", 14, theme.muted);
        ChatDesign.text(notice, ChatDesign.Role.META);
        notice.setMaxLines(3);
        notice.setVisibility(GONE);
        sheet.addView(notice, spaced(-1, -2, 6));

        composerArea = column();
        composerArea.setBackground(theme.rounded(getContext(), theme.raised, 6, false));
        composerArea.setElevation(dp(2));
        composerArea.setOutlineAmbientShadowColor(0x33000000);
        composerArea.setOutlineSpotShadowColor(0x33000000);
        composerArea.setPadding(0, 0, 0, 0);
        composer = new EditText(getContext());
        composer.setIncludeFontPadding(false);
        composer.setContentDescription(getContext().getString(R.string.chat_input));
        composer.setHint(getContext().getString(R.string.chat_input_hint));
        ChatDesign.text(composer, ChatDesign.Role.BODY);
        composer.setTextColor(theme.ink);
        composer.setHintTextColor(theme.muted);
        composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setMinLines(1);
        composer.setMinimumHeight(dp(48));
        composer.setPadding(dp(4), dp(12), dp(4), dp(12));
        composer.setMaxHeight(dp(144));
        composer.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        composer.setVerticalScrollBarEnabled(false);
        composer.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ChatDesign.text(composer, ChatDesign.Role.BODY);
        composerChinese = false;
        composer.setFilters(
                new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(16000)});
        attachmentPreview = column();
        composerArea.addView(attachmentPreview);
        composer.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (send != null) setBusy(busy);
            }
            public void afterTextChanged(android.text.Editable value) {
                if(composerToolbar!=null)composerToolbar.draftChanged();
                // The system may kill the process without hiding the assistant first.
                boolean chinese = TakuText.isChinese(value);
                if (chinese != composerChinese) {
                    composerChinese = chinese;
                    ChatDesign.text(composer, ChatDesign.Role.BODY);
                }
                saveDraft();
            }
        });
        jevAnalyze = action(getContext().getString(R.string.chat_suggest_screen), () -> host.suggestScreen());
        jevAnalyze.setContentDescription("Screen suggestions");
        // Explicit screen suggestions remain in the overflow menu, outside the message composer.
        jevCard = column();
        jevCard.setPadding(dp(8), dp(8), dp(8), dp(4));
        jevStatus = text("", 13, theme.ink);
        jevStatus.setContentDescription("Jev screen decision");
        jevCard.addView(jevStatus);
        LinearLayout jevActions = new LinearLayout(getContext());
        jevAccept = action("Hand to Agent · Include this screen", this::acceptJev);
        jevActions.addView(jevAccept, new LinearLayout.LayoutParams(0, -2, 1));
        jevActions.addView(action("Collapse", this::clearJev));
        jevCard.addView(jevActions);
        jevCard.setVisibility(GONE);
        composerArea.addView(jevCard);
        composerToolbar = new ComposerToolbar(getContext(), theme, repository(), controller::threadId,
                new ComposerToolbar.Actions() {
                    public void attach(String mime) {
                        String thread = controller.threadId();
                        if (!AgentRepository.validId(thread) || !currentThread(thread)) return;
                        saveDraft();
                        getContext().startActivity(new android.content.Intent(getContext(), ComposerAttachmentActivity.class)
                                .putExtra("conversationId",thread).putExtra("mime",mime)
                                .putExtra(AgentFrontendRoute.NATIVE_CONTROLS_ONLY,nativeControlsOnly)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                        host.dismiss();
                    }
                    public int insertion() { return composer.getSelectionStart(); }
                    public void dictated(String value,int position) { composer.getText().insert(Math.min(position,composer.length()),value);saveDraft(); }
                    public void message(String value) { notice(value); }
                    public void more(View anchor) { chatOptions(anchor); }
                    public boolean continuousReady(){
                        String thread=controller.threadId();
                        return page.equals("chat")&&isShown()&&!busy&&!jevPending&&!jevSubmitting&&AgentRepository.validId(thread)&&currentThread(thread)
                                &&activeTask.isEmpty()&&nativeTurn().isEmpty()&&!repository().isSubmitting(thread)&&!pendingSubmissions.containsKey(thread)
                                &&!ComposerPreferences.isSteering(repository().preferences(),thread)&&ComposerPreferences.draft(repository().preferences(),thread).isEmpty();
                    }
                    public boolean continuousInput(String value){
                        if(!continuousReady())return false;
                        String thread=controller.threadId();composer.setText(value);saveDraft();submit();
                        return pendingSubmissions.containsKey(thread);
                    }
                    public void interruptVoice(String taskId,java.util.function.Consumer<Boolean> result){
                        if(!page.equals("chat")||!isShown()||!currentThread(controller.threadId())){result.accept(false);return;}
                        if(activeTask.isEmpty()&&nativeTurn().isEmpty()){result.accept(true);return;}
                        if(!taskId.equals(activeTask)){result.accept(false);return;}
                        // This only requests cancellation. replyVoice reports the actual terminal snapshot.
                        repository().cancel(taskId,new AgentRepository.Callback<Boolean>(){
                            public void ready(Boolean accepted){result.accept(accepted);}
                            public void failed(String message){result.accept(false);}
                        });
                    }

                });
        LinearLayout tools = composerToolbar;
        // Keep one 48dp base row: tools, growing input, send/stop. Attachments
        // and dictation remain available through the same discoverable tools menu.
        // The editor remains the same node while streaming and when the keyboard resizes.
        tools.removeViewAt(1);
        tools.addView(composer, 1, new LinearLayout.LayoutParams(0, -2, 1));
        tools.setGravity(Gravity.BOTTOM);
        stop = icon(R.drawable.ic_stop, getContext().getString(R.string.chat_stop), this::cancel, false);
        tools.addView(stop, new LinearLayout.LayoutParams(dp(48),dp(48)));
        send = icon(R.drawable.ic_taku_arrow_up, getContext().getString(R.string.chat_send), this::submit, true);
        send.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        send.setPadding(dp(14),dp(14),dp(14),dp(14));
        // The supplied mobile button contract is r6; it takes precedence over the
        // circular send-button sketch. Keep the full 48dp target and 20dp glyph box.
        send.setBackground(theme.rounded(getContext(), theme.ink, 6, false));
        tools.addView(send, new LinearLayout.LayoutParams(dp(48),dp(48)));
        updateAttachmentPreview();
        composerArea.addView(tools);
        View composerDivider = new View(getContext());
        composerDivider.setBackgroundColor(theme.border);
        sheet.addView(composerDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        sheet.addView(composerArea, spaced(-1, -2, 12));
        if (!draftThread.isEmpty())
            composer.setText(repository().preferences().getString("draft-" + draftThread, ""));
        setBusy(busy);
        render();
        positionSheet();
    }

    private void updateAttachmentPreview() {
        if (attachmentPreview == null) return;
        attachmentPreview.removeAllViews();
        String thread = controller.threadId();
        JSONArray items = ComposerPreferences.attachments(repository().preferences(),thread);
        for (int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i); if(item==null)continue; final int index=i;
            LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);
            if(item.optString("mime").startsWith("image/")){
                android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(item.optString("path"),options);
                ImageView preview=new ImageView(getContext());preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setContentDescription(getContext().getString(R.string.chat_preview));
                if(options.outWidth>0&&options.outHeight>0&&Math.max(options.outWidth,options.outHeight)<=32768){int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>256)sample*=2;options.inJustDecodeBounds=false;options.inSampleSize=sample;preview.setImageBitmap(android.graphics.BitmapFactory.decodeFile(item.optString("path"),options));}
                row.addView(preview,new LinearLayout.LayoutParams(dp(48),dp(48)));
            }
            TextView label=text(item.optString("name"),12,theme.muted);label.setMaxLines(1);label.setEllipsize(TextUtils.TruncateAt.END);label.setPadding(dp(8),dp(8),dp(8),dp(8));row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            ImageButton remove=icon(R.drawable.ic_taku_close,getContext().getString(R.string.chat_remove_attachment)+" "+item.optString("name"),()->{
                if (!currentThread(thread)) return;
                JSONArray next=new JSONArray();for(int n=0;n<items.length();n++)if(n!=index)next.put(items.opt(n));
                repository().preferences().edit().putString(ComposerPreferences.key(thread,"attachments"),next.toString()).apply();
                updateAttachmentPreview();
            },false);
            row.addView(remove,new LinearLayout.LayoutParams(dp(48),dp(48)));attachmentPreview.addView(row);
        }
        if (ComposerPreferences.hasFailedDelivery(repository().preferences(), thread)) {
            attachmentPreview.addView(action(ComposerPreferences.recoveryLabel(repository().preferences(), thread), () -> {
                if (!currentThread(thread)) return;
                saveDraft();
                String label = ComposerPreferences.recoveryLabel(repository().preferences(), thread);
                if (ComposerPreferences.restoreFailed(repository().preferences(), thread)) {
                    refreshDraft(thread);
                    notice(label.equals("Restore unconfirmed message") ? "Content restored; the service may already have received it. Check the conversation before sending again."
                            : label.equals("Restore saved draft") ? "Saved draft restored." : "Failed message restored. Review it before sending again.");
                } else notice("There is a new draft. Save or clear it before restoring the failed message.");
            }));
        }
    }

    private void positionSheet() {
        if (sheet != null) requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // VoiceInteractionSession adds content as wrap-content. Pin this host to the
        // available window bounds; measuring from the sheet itself would feed back.
        int height = MeasureSpec.getSize(heightSpec);
        if (height == 0) height = getResources().getDisplayMetrics().heightPixels;
        int fullHeight = getResources().getDisplayMetrics().heightPixels;
        int topGap = Math.round(fullHeight * .065f);
        int overlap =
                imeBottom > 0
                        ? Math.min(imeBottom, Math.max(0, height - (fullHeight - imeBottom)))
                        : navBottom;
        if (sheet != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) sheet.getLayoutParams();
            availableSheetHeight = Math.max(0, height - topGap - overlap);
            int minimum = Math.min(availableSheetHeight, dp(320));
            params.height = Math.max(minimum, Math.round(availableSheetHeight * sheetFraction));
            int composerLimit = dp(params.height < dp(500) ? 96 : 144);
            if (composer != null && composer.getMaxHeight() != composerLimit)
                composer.setMaxHeight(composerLimit);
            sheet.setPadding(dp(16), 0, dp(16), dp(imeBottom > 0 ? 12 : 16));
            params.bottomMargin = overlap;
        }
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    private boolean nativeControlsOnly;

    void show(String thread) {
        nativeControlsOnly = false;
        show(thread,false);
    }

    void showNativeControls(String thread) {
        nativeControlsOnly = true;
        show(thread,false);
    }

    private void show(String thread,boolean fresh) {
        WorkspaceFloatService.hide(getContext());
        clearJev();
        // Opening an approval or attachment editor does not authorize a queued Home request.
        pendingHomeId = nativeControlsOnly ? "" : repository().preferences().getString("home-pending-id", "");
        pendingHomeText = nativeControlsOnly ? "" : repository().preferences().getString("home-pending-text", "");
        pendingHomeThread = nativeControlsOnly ? "" : repository().preferences().getString("home-pending-thread", "");
        pendingHomeNew = !nativeControlsOnly && repository().preferences().getBoolean("home-pending-new-thread", false);
        // Every new system invocation opens the conversation, including after inspecting settings.
        saveDraft();
        stopPreferencePages();
        page = "chat";
        String requested = AgentRepository.validId(thread) ? thread : draftThread;
        restoreReadingThread = !fresh && readingPositions.containsKey(requested) ? requested : "";
        followResponse = restoreReadingThread.isEmpty();
        build();
        sheet.setTranslationY(0);
        String startThread = !pendingHomeId.isEmpty() && AgentRepository.validId(pendingHomeThread)
                ? pendingHomeThread : thread;
        controller.start(startThread, fresh || !pendingHomeId.isEmpty() && pendingHomeNew);
        if (page.equals("settings") && preferencesView != null) preferencesView.activate();
        if (page.equals("account") && accountView != null) accountView.activate();
        post(
                () -> {
                    sheet.setTranslationY(sheet.getHeight());
                    if (android.animation.ValueAnimator.areAnimatorsEnabled())
                        sheet.animate().translationY(0).setDuration(getResources().getInteger(R.integer.taku_motion_duration_drawer))
                                .setInterpolator(new android.view.animation.PathInterpolator(.22f, 1f, .36f, 1f)).start();
                    else sheet.setTranslationY(0);
                });
    }

    void hide() {
        rememberReadingPosition();
        if (composerToolbar != null) composerToolbar.cancelVoice();
        if (workspacePreview != null) workspacePreview.stop();
        NativeTurnView.stopReading(null);
        keyboardTask = "";
        pendingAcceptedJev = null;
        clearJev();
        stopPreferencePages(false);
        saveDraft();
        controller.stop();
        ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(composer.getWindowToken(), 0);
    }

    void modelConnections() {
        nativePage();
        nativeReturnPage = "settings";
        if (nativeView != null) {
            nativeView.connections();
            nativeView.navigationBack = null;
        }
    }

    void jevConnections() {
        nativePage(); nativeReturnPage = "settings";
        if (nativeView != null) { nativeView.jevConnections(); nativeView.navigationBack = null; }
    }

    void aiUsage() {
        nativePage(); nativeReturnPage = "settings";
        if (nativeView != null) { nativeView.usage(); nativeView.navigationBack = null; }
    }

    void destroy() {
        if(composerToolbar!=null)composerToolbar.cancelVoice();
        NativeTurnView.stopReading(null);
        destroyed = true;
        if (workspacePreview != null) workspacePreview.stop();
        saveDraft();
        clearJev();
        stopPreferencePages(false);
        controller.close();
    }

    boolean back() {
        if (page.equals("skills") && skillsView != null && skillsView.back()) return true;
        if (page.equals("native") && nativeView != null && nativeView.back()) return true;
        if (page.equals("native")) { returnFromNative(); return true; }
        if (page.equals("account") || page.equals("harness") || page.equals("skills")) {
            settings();
            return true;
        }
        if (!page.equals("chat")) {
            stopPreferencePages();
            page = "chat";
            render();
            if (conversation != null) setConversationTitle(conversation);
            return true;
        }
        return false;
    }

    private void setConversationTitle(AgentConversation value) {
        title.setText(value.entries.isEmpty() && value.title.equals("New conversation")
                ? getContext().getString(R.string.chat_new) : value.title);
        ChatDesign.text(title, ChatDesign.Role.CONTEXT);
    }

    private void saveDraft() {
        if (composer == null || draftThread.isEmpty()) return;
        String text = composer.getText().toString();
        // TextWatcher persists every edit. A lifecycle call must not write an acknowledged old view back.
        if (text.equals(savedComposerText)) return;
        ComposerPreferences.saveDraft(repository().preferences(), draftThread, text);
        savedComposerText = text;
    }

    private boolean currentThread(String thread) {
        return !destroyed && thread.equals(controller.threadId()) && thread.equals(draftThread);
    }

    private void refreshDraft(String thread) {
        if (!currentThread(thread)) return;
        String text = repository().preferences().getString("draft-" + thread, "");
        if (!text.equals(composer.getText().toString())) {
            boolean acknowledgedVoice=composerToolbar!=null&&composerToolbar.continuous!=null&&composerToolbar.continuous.getActive();
            if(acknowledgedVoice)composerToolbar.applyingVoice=true;
            try{composer.setText(text);}finally{if(acknowledgedVoice)composerToolbar.applyingVoice=false;}
        }
        updateAttachmentPreview();
    }

    private void dismissSubmittedKeyboard() {
        if (!page.equals("chat") || !isShown() || !ComposerPreferences.draft(
                repository().preferences(), controller.threadId()).isEmpty()) return;
        // Keep editor focus so the Mac hardware/IME bridge can continue typing.
        ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(composer.getWindowToken(), 0);
    }

    private void reconcileSubmittedKeyboard(JSONObject task, ComposerDraft beforeReconcile) {
        if (keyboardTask.isEmpty()) return;
        if (!keyboardDraftRevision.equals(beforeReconcile.revision) || !beforeReconcile.isEmpty()) {
            keyboardTask = "";
            return;
        }
        if (task == null || !keyboardTask.equals(task.optString("id"))) return;
        String state = task.optString("state");
        if (state.equals("failed") || state.equals("needs_user")) {
            // A turn can be accepted before an asynchronous upstream error. Reopen only
            // when reconciliation restored this send into the untouched focused editor.
            if (keyboardDismissed && page.equals("chat") && isShown() && composer.hasFocus()
                    && composer.length() > 0) {
                ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                        .showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT);
            }
            keyboardTask = "";
            return;
        }
        // Queue publication alone is not acceptance by Codex.
        if (!keyboardHandled && (task.optBoolean("turnStarted") || state.equals("completed"))) {
            keyboardHandled = true;
            keyboardDismissed = imeBottom > 0;
            dismissSubmittedKeyboard();
        }
        if (state.equals("completed") || state.equals("cancelled")) {
            keyboardTask = "";
        }
    }

    @Override
    public void conversation(AgentConversation value, JSONObject latest) {
        if (!value.id.equals(draftThread)) {
            rememberReadingPosition();
            if (composerToolbar != null) composerToolbar.cancelVoice();
            NativeTurnView.stopReading(null);
            keyboardTask = "";
            clearJev();
            saveDraft();
            draftThread = value.id;
            composer.setText(repository().preferences().getString("draft-" + value.id, ""));
        }
        conversation = value;
        composer.setEnabled(true);
        if(pendingHomeNew&&!pendingHomeId.isEmpty()) {
            if(repository().preferences().edit().putString("home-pending-thread",value.id).remove("home-pending-new-thread").commit()) {
                pendingHomeThread=value.id;pendingHomeNew=false;
            } else {notice("New conversation opened, but the request was not saved. Reopen Agent.");return;}
        }
        updateAttachmentPreview();
        snapshot = latest;
        java.util.Set<String> pendingKeys=new java.util.HashSet<>();
        JSONObject nativeState=latest.optJSONObject("native");
        JSONArray pending=nativeState==null?null:nativeState.optJSONArray("requests");
        if(pending!=null)for(int i=0;i<pending.length();i++)pendingKeys.add(pending.optJSONObject(i).optString("key"));
        NativeRequestView.drafts.keySet().retainAll(pendingKeys);
        JSONObject task = latest.optJSONObject("task");
        String state = task == null ? "" : task.optString("state");
        ComposerDraft beforeReconcile = ComposerPreferences.draft(repository().preferences(), value.id);
        ComposerPreferences.reconcile(repository().preferences(), value.id, task,
                repository().isSubmitting(value.id));
        boolean unconfirmedSteer = ComposerPreferences.reconcileSteer(repository().preferences(), value.id);
        refreshDraft(value.id);
        reconcileSubmittedKeyboard(task, beforeReconcile);
        activeTask = value.activeTask(latest);
        if (page.equals("chat")
                && (state.equals("needs_user")
                        || state.equals("failed")
                        || state.equals("cancelled"))) notice(task.optString("message"));
        else if (unconfirmedSteer) notice("The last follow-up instruction is unconfirmed and was preserved. The service may have received it; check the conversation before sending again.");
        else if (composerToolbar == null || !composerToolbar.preservesVoiceNotice()) notice.setVisibility(GONE);
        if (page.equals("chat")) setConversationTitle(value);
        setBusy(false);
        if (!NativeRequestView.editing || latest.optJSONObject("native")==null || latest.optJSONObject("native").optJSONArray("requests")==null || latest.optJSONObject("native").optJSONArray("requests").length()==0) {
            NativeRequestView.editing=false; render();
        }
        if (!pendingHomeId.isEmpty() && !pendingHomeText.isEmpty() && activeTask.isEmpty() && nativeTurn().isEmpty()
                && (pendingHomeThread.isEmpty()||pendingHomeThread.equals(value.id))
                && !pendingSubmissions.containsKey(value.id) && !repository().isSubmitting(value.id)
                && !ComposerPreferences.isSteering(repository().preferences(), value.id)) {
            if (ComposerPreferences.applyHome(repository().preferences(), value.id, pendingHomeId, pendingHomeText)) {
                refreshDraft(value.id);
                submit();
            } else notice("Could not save the current draft. The home request was not sent. Retry.");
        }
        if(composerToolbar!=null&&task!=null&&page.equals("chat")) {
            StringBuilder spoken=new StringBuilder();String answerTurn=latest.optJSONObject("runtime")==null?"":latest.optJSONObject("runtime").optString("turnId");
            for(NativeTurnPresentation.Block block:NativeTurnPresentation.blocks(value))if(block.user==null) {
                // Require the native turn bound to this task; queued snapshots may still contain older history.
                if(answerTurn.isEmpty()||!block.key.startsWith("turn/"+answerTurn+"/"))continue;
                for(AgentConversation.Entry entry:block.answers)if(entry.kind==AgentConversation.Kind.ASSISTANT)spoken.append(NativeTurnPresentation.visibleText(entry.text,block.active)).append("\n\n");
            }
            boolean terminal=state.equals("completed")||state.equals("failed")||state.equals("needs_user")||state.equals("cancelled");
            composerToolbar.replyVoice(task.optString("id"),NativeMarkdown.speechText(spoken.toString()),terminal,state.equals("completed"));
        }
        if (pendingAcceptedJev != null) {
            JevScreenDecision accepted = pendingAcceptedJev;
            pendingAcceptedJev = null;
            acceptSuggestedJev(accepted);
        }
    }

    @Override
    public void selectionStarted() {
        saveDraft();
        conversation = null;
        snapshot = new JSONObject();
        activeTask = keyboardTask = "";
        if (page.equals("chat")) title.setText("Reading conversation…");
        composer.setEnabled(false);
        notice("");
        render();
    }

    @Override
    public void selectionFailed(String message, Runnable retry) {
        // Account/model settings do not depend on the selected chat loading.
        if (!page.equals("chat")) return;
        title.setText("Conversation temporarily unavailable");
        notice(message + " Tap to retry.");
        notice.setContentDescription("Reload conversation");
        notice.setOnClickListener(view -> retry.run());
        notice.setClickable(true);
    }

    @Override
    public void notice(String message) {
        notice.setOnClickListener(null);
        notice.setClickable(false);
        notice.setContentDescription(null);
        notice.setText(message);
        notice.setVisibility(message.isEmpty() ? GONE : VISIBLE);
    }

    @Override
    public void busy(boolean value) {
        setBusy(value);
    }

    private void setBusy(boolean value) {
        busy = value;
        for (ImageButton button : new ImageButton[]{headerHistory, headerNew}) {
            if (button == null) continue;
            button.setEnabled(!busy);
            button.setAlpha(busy ? .45f : 1f);
        }
        if (send == null) return;
        boolean empty = composer.getText().toString().trim().isEmpty()
                && ComposerPreferences.attachments(repository().preferences(), controller.threadId()).length() == 0;
        send.setEnabled(
                !empty && !value && !jevPending && !jevSubmitting
                        && composer.isEnabled() && conversation != null
                        && conversation.id.equals(controller.threadId())
                        && !pendingSubmissions.containsKey(controller.threadId())
                        && currentThread(controller.threadId())
                        && !repository().isSubmitting(controller.threadId())
                        && !ComposerPreferences.isSteering(repository().preferences(), controller.threadId())
                        && (activeTask.isEmpty() || !nativeTurn().isEmpty())
                        && AgentRepository.validId(controller.threadId()));
        if (jevAnalyze != null) {
            boolean enabled = true; // An explicit request skips Jev and uses the image model.
            jevAnalyze.setVisibility(enabled ? VISIBLE : GONE);
            boolean available = !value && !jevPending && !jevSubmitting
                    && !pendingSubmissions.containsKey(controller.threadId())
                    && !repository().isSubmitting(controller.threadId())
                    && !ComposerPreferences.isSteering(repository().preferences(), controller.threadId())
                    && currentThread(controller.threadId()) && AgentRepository.validId(controller.threadId())
                    && activeTask.isEmpty() && nativeTurn().isEmpty();
            // Generating an offer creates no task and must not wait for a conversation RPC.
            jevAnalyze.setEnabled(!jevPending && !jevSubmitting);
            jevAnalyze.setText(jevPending ? "Generating suggestions…" : "Screen suggestions");
            if (jevAccept != null) jevAccept.setEnabled(enabled && available
                    && jevDecision != null && !jevDecision.expired());
        }
        send.setAlpha(send.isEnabled() ? 1f : .45f);
        send.setContentDescription(getContext().getString(nativeTurn().isEmpty() ? R.string.chat_send : R.string.chat_steer));
        boolean executing = !activeTask.isEmpty() || !nativeTurn().isEmpty();
        stop.setVisibility(executing && page.equals("chat") ? VISIBLE : GONE);
        send.setVisibility(executing && empty ? GONE : VISIBLE);
    }

    private void render() {
        if (!page.equals("chat")) return;
        int previousScroll = scroll.getScrollY();
        String thread = conversation == null ? "" : conversation.id;
        boolean reset = transcript == null || transcript.getParent() != messages
                || !thread.equals(renderedThread);
        if (reset && readingPositions.containsKey(thread)) restoreReadingThread = thread;
        boolean followLatest = followResponse || reset
                || previousScroll + scroll.getHeight() >= messages.getHeight() - dp(24);
        View anchor = reset ? null : transcript.scrollAnchor(previousScroll);
        int anchorOffset = anchor == null ? 0 : previousScroll - transcript.getTop() - anchor.getTop();
        renderedThread = thread;
        if (reset) {
            if (workspacePreview != null) workspacePreview.stop();
            messages.removeAllViews();
            transcript = new NativeConversationView(getContext(), theme, repository().preferences(), thread);
            timelineHost = column();
            queuedHost = column();
            messages.addView(transcript);
            workspacePreview = new ChatWorkspacePreview(getContext(), theme, this::openWorkspace);
            messages.addView(workspacePreview);
            messages.addView(timelineHost);
            messages.addView(queuedHost);
            timelineSignature = queueSignature = "";
        }
        composerArea.setVisibility(VISIBLE);
        compactInspectionHeader(false);
        JSONObject nativeState = snapshot.optJSONObject("native");
        JSONObject outputs = nativeState == null ? null : nativeState.optJSONObject("outputs");
        transcript.bind(conversation, outputs);
        workspacePreview.start(thread);
        JSONArray requests = nativeState == null ? null : nativeState.optJSONArray("requests");
        String requestKeys = requests == null ? "" : requests.toString();
        boolean showQuestion = !requestKeys.isEmpty() && !requestKeys.equals("[]")
                && !requestKeys.equals(renderedRequestKeys);
        renderedRequestKeys = requestKeys;
        // Output deltas belong to their native items and must not recreate approvals/controls.
        JSONObject timelineState = new JSONObject();
        if (nativeState != null) for (String key : new String[]{"plan", "goal", "error", "requests", "diff"}) {
            try { if (nativeState.has(key)) timelineState.put(key, nativeState.get(key)); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        String nextTimeline = timelineState.toString();
        if (!nextTimeline.equals(timelineSignature)) {
            timelineHost.removeAllViews();
            timelineHost.addView(new NativeTimelineView(getContext(), theme, timelineState, transcript.expansion()));
            timelineSignature = nextTimeline;
        }
        JSONObject request = snapshot.optJSONObject("request");
        JSONObject task = snapshot.optJSONObject("task");
        JSONObject runtime = snapshot.optJSONObject("runtime");
        boolean turnStarted = runtime != null && runtime.optBoolean("turnStarted");
        boolean queued = task != null && request != null && !turnStarted
                && !task.optString("state").equals("completed");
        String nextQueue = queued ? request.optString("prompt") + "/" + activeTask : "";
        if (!nextQueue.equals(queueSignature)) {
            queuedHost.removeAllViews();
            if (queued) {
                queuedHost.addView(NativeConversationView.userMessage(getContext(), theme,
                        request.optString("prompt"), Collections.emptyList()), spaced(-1, -2, 18));
                queuedHost.addView(text(getContext().getString(R.string.chat_queued),
                        14, theme.muted), spaced(-1, -2, 6));
            }
            queueSignature = nextQueue;
        }
        // Follow new output only at the bottom. Reading older turns keeps the same keyed row
        // and offset, even if an earlier message moves into its native work log. Never move focus.
        NativeConversationView rendered = transcript;
        int expectedReadingVersion = readingInteractionVersion;
        ReadingPosition restored = thread.equals(restoreReadingThread) ? readingPositions.get(thread) : null;
        ScrollView viewport = scroll;
        viewport.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                viewport.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!page.equals("chat") || scroll != viewport || transcript != rendered) return true;
                if (readingInteractionVersion != expectedReadingVersion) return true;
                boolean pending = requests != null && requests.length() > 0;
                if (showQuestion) { layoutReadingPosition = null; restoreReadingThread = ""; }
                if (restored != null && !showQuestion && thread.equals(restoreReadingThread)) {
                    layoutReadingPosition = restored;
                    layoutReadingThread = thread;
                    restoreScroll(restored, rendered);
                    int length = composer.length();
                    if (restored.selectionStart >= 0 && restored.selectionEnd >= 0)
                        composer.setSelection(Math.min(length, restored.selectionStart), Math.min(length, restored.selectionEnd));
                    followResponse = restored.follow;
                    restoreReadingThread = "";
                }
                else if (showQuestion || followResponse && pending) scroll.scrollTo(0, timelineHost.getTop());
                else if (followLatest) scroll.scrollTo(0, Math.max(0, messages.getHeight() - scroll.getHeight()));
                else if (anchor != null && anchor.getParent() == rendered)
                    scroll.scrollTo(0, rendered.getTop() + anchor.getTop() + anchorOffset);
                else scroll.scrollTo(0, previousScroll);
                return true;
            }
        });
    }

    private void restoreScroll(ReadingPosition saved, NativeConversationView rendered) {
        View row = rendered.rowForKey(saved.key);
        if (saved.follow) scroll.scrollTo(0, Math.max(0, messages.getHeight() - scroll.getHeight()));
        else scroll.scrollTo(0, row == null ? saved.scroll
                : rendered.getTop() + row.getTop() + saved.offset);
    }

    private void chatOptions() {
        chatOptions(headerSettings.isShown() ? headerSettings : composerToolbar.getChildAt(0));
    }

    private void chatOptions(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        String[] labels = {getContext().getString(R.string.chat_settings), getContext().getString(R.string.chat_workspaces),
                getContext().getString(R.string.chat_capabilities), getContext().getString(R.string.chat_model),
                getContext().getString(R.string.chat_effort), getContext().getString(R.string.chat_new),
                getContext().getString(R.string.chat_suggest_screen)};
        for (int i = 0; i < labels.length; i++) menu.getMenu().add(0, i, i, labels[i]);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0: settings(); break;
                case 1: host.openMirror(); break;
                case 2: nativePage(); break;
                case 3: composerToolbar.models(); break;
                case 4: composerToolbar.efforts(); break;
                case 5: newConversation(); break;
                case 6: host.suggestScreen(); break;
            }
            return true;
        });
        menu.show();
    }

    private void submit() {
        if(composerToolbar!=null)composerToolbar.beforeSubmit();
        NativeTurnView.stopReading(null);
        String thread = controller.threadId();
        if (busy || !composer.isEnabled() || conversation == null || !thread.equals(conversation.id)
                || pendingSubmissions.containsKey(thread) || repository().isSubmitting(thread)
                || ComposerPreferences.isSteering(repository().preferences(), thread)
                || !AgentRepository.validId(thread) || !currentThread(thread)) return;
        saveDraft();
        ComposerDraft sent = ComposerPreferences.draft(repository().preferences(), thread);
        String input = composer.getText().toString().trim();
        if(input.isEmpty() && ComposerPreferences.attachments(repository().preferences(),controller.threadId()).length()>0)input="Please see the attachment.";
        if (input.isEmpty() || jevPending || jevSubmitting) return;
        layoutReadingPosition = null;
        restoreReadingThread = "";
        readingInteractionVersion++;
        boolean steering = !nativeTurn().isEmpty();
        if (!steering && !activeTask.isEmpty()) return;
        clearJev();
        String homeId = pendingHomeId;
        // Once copied to a persistent draft, Home's handoff must never retry automatically.
        if (!homeId.isEmpty()) {
            ComposerPreferences.HomeConsumption home = ComposerPreferences.consumeHome(repository().preferences(), thread, homeId);
            if (home == ComposerPreferences.HomeConsumption.FAILED) {
                notice("Could not save send status. The draft was preserved. Retry.");
                return;
            }
            if (home == ComposerPreferences.HomeConsumption.CONSUMED) {
                pendingHomeId = "";
                pendingHomeText = "";
                pendingHomeThread = "";
            }
        }
        if (steering) { steer(sent, input); return; }
        pendingSubmissions.put(thread, sent);
        setBusy(true);
        EditText submittedComposer = composer;
        repository()
                .submit(
                        sent,
                        input,
                        host.foreground(),
                        host.screenshot(),
                        new AgentRepository.Callback<String>() {
                            public void ready(String id) {
                                pendingSubmissions.remove(thread, sent);
                                try {
                                    ComposerPreferences.reconcile(repository().preferences(), thread,
                                            new JSONObject().put("id", id).put("state", "queued").put("published", true), false);
                                } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
                                if (!currentThread(thread)) return;
                                activeTask = id;
                                if(composerToolbar!=null)composerToolbar.acceptedVoice(id);
                                followResponse = true;
                                refreshDraft(thread);
                                if (composer == submittedComposer && isShown() && page.equals("chat")) {
                                    ComposerDraft remaining = ComposerPreferences.draft(repository().preferences(), thread);
                                    if (remaining.isEmpty()) {
                                        keyboardTask = id;
                                        keyboardDraftRevision = remaining.revision;
                                        keyboardHandled = false;
                                        keyboardDismissed = false;
                                    }
                                }
                                notice("Queued");
                                setBusy(false);
                            }

                            public void failed(String message) {
                                pendingSubmissions.remove(thread, sent);
                                if (!currentThread(thread)) return;
                                if(composerToolbar!=null)composerToolbar.cancelVoice();
                                refreshDraft(thread);
                                setBusy(false);
                                notice(message);
                            }
                        });
    }

    private void clearJev() {
        jevGeneration++;
        jevPending = false;
        jevDecision = null;
        if (jevCard != null) jevCard.setVisibility(GONE);
        if (send != null) setBusy(busy);
    }

    private void analyzeJev() {
        String current = controller.threadId();
        if (busy || jevPending || jevSubmitting || !activeTask.isEmpty() || !nativeTurn().isEmpty()
                || !AgentRepository.validId(current) || !currentThread(current)
                || repository().isSubmitting(current) || pendingSubmissions.containsKey(current)
                || ComposerPreferences.isSteering(repository().preferences(), current)) return;
        clearJev();
        jevCard.setVisibility(VISIBLE);
        jevAccept.setVisibility(GONE);
        if (!repository().preferences().getBoolean("includeScreenshot", false)) {
            jevStatus.setText("Enable \"Include main-screen screenshot when sending\" in settings, return to the target app, and open the assistant again. Tapping Analyze sends this screen to Jev.");
            return;
        }
        final android.graphics.Bitmap image;
        try { image = JevScreenDecision.capture(host.screenshot()); }
        catch (Exception e) { jevStatus.setText("No screen from before invocation was captured. Return to the target app and open the assistant again."); return; }
        ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(composer.getWindowToken(), 0);
        final int generation = jevGeneration;
        final String thread = controller.threadId();
        jevRequest = composer.getText().toString().trim();
        jevPending = true;
        jevStatus.setText("Sending the screen from before this invocation to Jev for analysis…");
        setBusy(busy);
        repository().analyzeJev(image, host.pageText(), jevRequest, host.foreground(), android.os.SystemClock.elapsedRealtime(),
                new AgentRepository.Callback<JevScreenDecision>() {
                    public void ready(JevScreenDecision decision) {
                        if (generation != jevGeneration || !thread.equals(controller.threadId())) return;
                        jevPending = false;
                        jevDecision = decision;
                        jevStatus.setText(decision.summary() + "\n" + (decision.actionable ? decision.suggestion : "There is not enough confidence for a suggestion. You can send a request directly."));
                        jevAccept.setVisibility(decision.actionable ? VISIBLE : GONE);
                        setBusy(busy);
                        postDelayed(() -> { if (generation == jevGeneration && jevDecision != null && jevDecision.expired()) {
                            jevStatus.setText("This screen suggestion has expired. Open the assistant again to analyze it.");
                            jevAccept.setEnabled(false);
                        } }, JevScreenDecision.MAX_AGE_MS);
                    }
                    public void failed(String message) {
                        if (generation != jevGeneration) return;
                        jevPending = false; jevStatus.setText(message); setBusy(busy);
                    }
                });
    }

    private void acceptJev() {
        String thread = controller.threadId();
        if (busy || jevPending || jevSubmitting || jevDecision == null || jevDecision.expired()
                || !activeTask.isEmpty() || !nativeTurn().isEmpty() || !AgentRepository.validId(thread)
                || !currentThread(thread) || repository().isSubmitting(thread)
                || pendingSubmissions.containsKey(thread)
                || ComposerPreferences.isSteering(repository().preferences(), thread)) return;
        JevScreenDecision accepted = jevDecision;
        final int generation = jevGeneration;
        jevSubmitting = true;
        setBusy(busy);
        repository().submitJev(thread, accepted, new AgentRepository.Callback<String>() {
            public void ready(String id) {
                jevSubmitting = false;
                if (generation != jevGeneration || !thread.equals(controller.threadId())) {
                    setBusy(busy);
                    return;
                }
                activeTask = id; followResponse = true;
                if (!accepted.request.isEmpty() && composer.getText().toString().trim().equals(accepted.request)) {
                    composer.setText("");
                    saveDraft();
                }
                clearJev(); setBusy(false); notice("Suggestion and screen handed to Agent");
            }
            public void failed(String message) {
                jevSubmitting = false;
                if (generation != jevGeneration || !thread.equals(controller.threadId())) {
                    setBusy(busy);
                    return;
                }
                jevStatus.setText(message); setBusy(false);
            }
        });
    }

    void showWithJev(JevScreenDecision decision) {
        // Accept this screen in its own thread, preserving unrelated drafts and history.
        show(null,true);
        pendingHomeId = "";
        pendingHomeText = "";
        pendingAcceptedJev = decision;
    }

    private void acceptSuggestedJev(JevScreenDecision decision) {
        clearJev();
        jevDecision = decision;
        jevCard.setVisibility(VISIBLE);
        jevStatus.setText(decision.summary() + "\n" + decision.suggestion);
        jevAccept.setVisibility(VISIBLE);
        if (!AgentRepository.validId(controller.threadId()) || busy || !activeTask.isEmpty() || !nativeTurn().isEmpty()) {
            jevStatus.append("\nThe current conversation is not ready or is running. Tap Hand to Agent when it is ready.");
            setBusy(busy);
            return;
        }
        acceptJev();
    }

    private String nativeTurn() {
        return conversation == null ? "" : conversation.activeTurn(snapshot);
    }

    private void steer(ComposerDraft sent, String input) {
        String thread = sent.thread;
        String token = "";
        try {
            JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text",input));
            JSONArray attachments=new JSONArray(sent.attachments);
            repository().validateAttachments(attachments);
            for(int i=0;i<attachments.length();i++){
                JSONObject item=attachments.getJSONObject(i);java.io.File file=new java.io.File(item.getString("path"));
                String canonical=file.getCanonicalPath();
                content.put(item.optString("mime").startsWith("image/")
                    ?new JSONObject().put("type","localImage").put("path",canonical)
                    :new JSONObject().put("type","text").put("text","User-selected attachment: "+item.optString("name")+"\nLocal path: "+canonical));
            }
            JSONObject params=new JSONObject().put("threadId",thread).put("expectedTurnId",nativeTurn()).put("input",content);
            token = ComposerPreferences.beginSteer(repository().preferences(), sent);
            final String delivery = token;
            pendingSubmissions.put(thread, sent);
            setBusy(true);
            EditText submittedComposer = composer;
            NativeClient.rpc("turn/steer",params,new NativeClient.Callback(){
                public void ready(JSONObject result){
                    ComposerPreferences.finishSteer(repository().preferences(), thread, delivery, true);
                    pendingSubmissions.remove(thread, sent);
                    if (!currentThread(thread)) return;
                    refreshDraft(thread);
                    if (composer == submittedComposer) dismissSubmittedKeyboard();
                    setBusy(false);notice("Added to the current execution");
                }
                public void failed(String message){
                    ComposerPreferences.finishSteer(repository().preferences(), thread, delivery, false);
                    pendingSubmissions.remove(thread, sent);
                    if (!currentThread(thread)) return;
                    refreshDraft(thread);setBusy(false);notice(message);
                }
            });
        }catch(Exception e){
            if (!token.isEmpty()) ComposerPreferences.finishSteer(repository().preferences(), thread, token, false);
            pendingSubmissions.remove(thread, sent);
            if (currentThread(thread)) { refreshDraft(thread);setBusy(false);notice(e instanceof AgentRepository.InvalidAttachment
                    ? "The attachment is unavailable. The draft was preserved. Select the attachment again." : "Could not add the follow-up. The draft was preserved. Retry."); }
        }
    }

    private void cancel() {
        String turn = nativeTurn();
        if(!turn.isEmpty()){
            String thread = controller.threadId();
            JSONObject nativeState=snapshot.optJSONObject("native");
            JSONObject goal=nativeState==null?null:nativeState.optJSONObject("goal");
            interruptTurn(thread, turn, goal);
            return;
        }
        repository()
                .cancel(
                        activeTask,
                        new AgentRepository.Callback<Boolean>() {
                            public void ready(Boolean accepted) {
                                notice("Stopping; completed actions will not be automatically undone.");
                            }

                            public void failed(String message) {
                                notice(message);
                            }
                        });
    }

    private void interruptTurn(String thread, String turn, JSONObject goal) {
        if(goal!=null&&goal.optString("status").equals("active"))try{
            NativeClient.rpc("thread/goal/set",new JSONObject().put("threadId",thread).put("status","paused").put("origin","user"),new NativeClient.Callback(){public void ready(JSONObject r){}public void failed(String m){if(currentThread(thread))notice(m);}});
        }catch(Exception ignored){}
        try{JSONObject params=new JSONObject().put("threadId",thread).put("turnId",turn);
            NativeClient.rpc("turn/interrupt",params,new NativeClient.Callback(){public void ready(JSONObject r){if(currentThread(thread))notice("Stop request delivered");}public void failed(String m){if(currentThread(thread))notice(m);}});
        }catch(Exception ignored){}
    }

    private void sessions() {
        stopPreferencePages();
        saveDraft();
        page = "sessions";
        title.setText(getContext().getString(R.string.chat_history));
        composerArea.setVisibility(GONE);
        compactInspectionHeader(true);
        messages.removeAllViews();
        messages.addView(
                action(
                        getContext().getString(R.string.chat_new),
                        this::newConversation));
        repository()
                .rpc(
                        "thread/list",
                        new JSONObject(),
                        new AgentRepository.Callback<JSONObject>() {
                            public void ready(JSONObject result) {
                                if (!page.equals("sessions")) return;
                                JSONArray data = result.optJSONArray("data");
                                if (data == null) return;
                                List<JSONObject> rows = new ArrayList<>();
                                for (int i = 0; i < data.length(); i++)
                                    rows.add(data.optJSONObject(i));
                                Collections.reverse(rows);
                                for (JSONObject thread : rows) {
                                    if (thread == null) continue;
                                    String name = thread.optString("name", "").trim();
                                    String label = (name.isEmpty() || name.equals("null"))
                                                    && thread.optString("preview", "").trim().isEmpty()
                                            ? getContext().getString(R.string.chat_new)
                                            : AgentConversation.title(thread);
                                    TextView item =
                                            text(label, 16, theme.ink);
                                    item.setPadding(dp(4), dp(16), dp(4), dp(16));
                                    item.setMinHeight(dp(52));
                                    item.setOnClickListener(
                                            view -> {
                                                stopPreferencePages();
                                                page = "chat";
                                                activeTask = "";
                                                controller.select(thread.optString("id"));
                                            });
                                    messages.addView(item);
                                }
                            }

                            public void failed(String message) {
                                notice(message);
                            }
                        });
    }

    private void newConversation() {
        if (busy) return;
        saveDraft();
        stopPreferencePages();
        page = "chat";
        compactInspectionHeader(false);
        conversation = null;
        snapshot = new JSONObject();
        activeTask = "";
        title.setText(getContext().getString(R.string.chat_new));
        controller.newConversation();
        render();
    }

    private void settings() {
        if(composerToolbar!=null)composerToolbar.cancelVoice();
        NativeTurnView.stopReading(null);
        saveDraft();
        stopPreferencePages();
        page = "settings";
        notice.setVisibility(GONE);
        title.setText("Your Taku");
        composerArea.setVisibility(GONE);
        messages.removeAllViews();
        preferencesView =
                new AgentSettingsView(
                        getContext(),
                        theme,
                        repository(),
                        conversation == null ? "New conversation" : conversation.title,
                        new AgentSettingsView.Actions() {
                            public void back() {
                                stopPreferencePages();
                                page = "chat";
                                render();
                                if (conversation != null) setConversationTitle(conversation);
                            }

                            public void account() {
                                accountPage();
                            }

                            public void harness() { harnessPage(); }

                            public void skills() { skillsPage(); }

                            public void nativeControls() { nativePage(); }

                            public void nativeSection(String section) {
                                nativePage();
                                if (nativeView == null) return;
                                switch (section) {
                                    case "connection": nativeView.connections(); break;
                                    case "jev": nativeView.jevConnections(); break;
                                    case "models": nativeView.models(); break;
                                    case "tasks": nativeView.tasks(); break;
                                    case "apps": nativeView.extensions(); break;
                                    case "usage": nativeView.usage(); break;
                                }
                                nativeView.navigationBack = null;
                            }

                            public void theme(String selected) {
                                repository()
                                        .preferences()
                                        .edit()
                                        .putString("theme", selected)
                                        .apply();
                                build();
                                settings();
                            }

                            public void rename(String name) {
                                controller.rename(name);
                                notice("Title update requested");
                            }
                        });
        messages.addView(preferencesView);
        compactInspectionHeader(true);
        scroll.scrollTo(0, 0);
    }

    void accountPage() {
        stopPreferencePages();
        page = "account";
        notice.setVisibility(GONE);
        title.setText("Account connection");
        composerArea.setVisibility(GONE);
        messages.removeAllViews();
        accountView = new AgentAccountView(getContext(), theme, repository(), host, this::settings);
        messages.addView(accountView);
        compactInspectionHeader(true);
        scroll.scrollTo(0, 0);
    }

    private void harnessPage() {
        stopPreferencePages(); page="harness"; notice.setVisibility(GONE);
        title.setText("Context"); composerArea.setVisibility(GONE); messages.removeAllViews();
        harnessView = new AgentHarnessView(getContext(),theme,repository(),controller.threadId());
        messages.addView(harnessView);
        compactInspectionHeader(true);
        scroll.scrollTo(0,0);
    }

    private void skillsPage() {
        stopPreferencePages(); page="skills"; notice.setVisibility(GONE);
        title.setText("Skills"); composerArea.setVisibility(GONE); messages.removeAllViews();
        skillsView = new AgentSkillsView(getContext(),theme,repository(),controller.threadId(),this::settings);
        messages.addView(skillsView);
        compactInspectionHeader(true);
        scroll.scrollTo(0,0);
    }

    private void compactInspectionHeader(boolean compact) {
        if (headerNavigation == null || title == null || messages == null) return;
        boolean chatting = page.equals("chat");
        headerNavigation.setVisibility(chatting ? GONE : VISIBLE);
        headerHistory.setVisibility(chatting ? VISIBLE : GONE);
        headerNew.setVisibility(chatting ? VISIBLE : GONE);
        headerNavigation.setImageResource(R.drawable.ic_taku_chevron_left);
        headerNavigation.setRotation(0);
        headerNavigation.setContentDescription(getContext().getString(compact ? R.string.chat_back : R.string.chat_history));
        headerSettings.setImageResource(R.drawable.ic_taku_more);
        headerSettings.setContentDescription(getContext().getString(R.string.chat_more));
        for (ImageButton button : new ImageButton[]{headerNavigation, headerSettings})
            button.setBackgroundColor(Color.TRANSPARENT);
        headerSettings.setVisibility(page.equals("chat") || compact && page.equals("settings") ? GONE : VISIBLE);
        ChatDesign.text(title, ChatDesign.Role.CONTEXT);
        messages.setPadding(0, dp(compact ? 8 : 16), 0, dp(16));
    }

    private void stopPreferencePages() {
        stopPreferencePages(true);
    }

    private void stopPreferencePages(boolean resetHeader) {
        rememberReadingPosition();
        if (composerToolbar != null) composerToolbar.cancelVoice();
        if (workspacePreview != null) workspacePreview.stop();
        if (resetHeader && !page.equals("chat")) compactInspectionHeader(false);
        if (preferencesView != null) preferencesView.stop();
        if (accountView != null) accountView.stop();
        host.sensitive(false);
    }

    private void nativeModels() {
        nativePage();
        if(messages.getChildCount()>0&&messages.getChildAt(0) instanceof AgentNativeView)
            ((AgentNativeView)messages.getChildAt(0)).models();
    }

    private void nativePage() {
        if(composerToolbar!=null)composerToolbar.cancelVoice();
        NativeTurnView.stopReading(null);
        nativeReturnPage = page.equals("chat") ? "chat" : "settings";
        stopPreferencePages(); page = "native"; notice.setVisibility(GONE);
        title.setText("Native capabilities"); composerArea.setVisibility(GONE); messages.removeAllViews();
        try {
            nativeView = new AgentNativeView(getContext(), theme, repository(), controller.threadId(),
                    new AgentNativeView.Actions() {
                        public void back() { returnFromNative(); }
                        public void dismiss() { host.dismiss(); }
                        public void select(String id) { stopPreferencePages(); page = "chat"; activeTask = ""; controller.select(id); }
                        public void changed(String label) { title.setText(label); scroll.scrollTo(0,0); }
                    });
            messages.addView(nativeView);
            compactInspectionHeader(true);
        } catch (Exception e) { notice("Cannot read native protocol resources. Update the assistant component."); }
        scroll.scrollTo(0, 0);
    }

    private void returnFromNative() {
        if (!nativeReturnPage.equals("chat")) { settings(); return; }
        stopPreferencePages();
        page = "chat";
        render();
        if (conversation != null) setConversationTitle(conversation);
    }
}
