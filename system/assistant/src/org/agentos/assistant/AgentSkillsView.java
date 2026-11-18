package org.agentos.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.util.ArrayList;
import java.util.List;

/** Searchable skill objects; installation and configuration live in the selected skill's detail. */
final class AgentSkillsView extends LinearLayout {
    private final AgentSettingsUi ui;
    private final AgentRepository repository;
    private final String thread;
    private final Runnable back;
    private final LinearLayout body;
    private final TextView status;
    private JSONArray installed = new JSONArray(), selected = new JSONArray(), sources = new JSONArray();
    private JSONArray catalog = new JSONArray();
    private JSONObject catalogSource = new JSONObject();
    private JSONObject updateCheck;
    private String detailName;
    private String sourceId = "openai", query = "", category = "All";
    private boolean installedMode, busy, catalogLoaded, confirmUninstall;
    private LinearLayout rows;
    private boolean sourceForm;

    boolean back() {
        if (detailName != null || sourceForm) { renderLibrary(); return true; }
        return false;
    }

    AgentSkillsView(Context context, DrawerTheme theme, AgentRepository repository, String thread, Runnable back) {
        super(context);
        setOrientation(VERTICAL);
        ui = new AgentSettingsUi(context, theme);
        this.repository = repository;
        this.thread = thread;
        this.back = back;
        status = ui.text("Reading skills…", 12, false);
        status.setContentDescription("Skills status");
        body = ui.column();
        addView(body, new LayoutParams(-1, -2));
        renderLibrary();
        loadInstalled(true);
    }

    private static JSONArray array(JSONObject value, String field) {
        JSONArray result = value.optJSONArray(field);
        return result == null ? new JSONArray() : result;
    }

    private JSONObject params(String... pairs) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]); }
        catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        return result;
    }

    private void rpc(String method, JSONObject params, java.util.function.Consumer<JSONObject> success) {
        if (busy) return;
        busy = true;
        status("Loading…");
        repository.rpc(method, params, new AgentRepository.Callback<JSONObject>() {
            public void ready(JSONObject result) { busy = false; status(""); success.accept(result); }
            public void failed(String message) { busy = false; status(message); }
        });
    }

    private void status(String copy) {
        status.setText(copy);
        status.setVisibility(copy.isEmpty() ? GONE : VISIBLE);
    }

    private void loadInstalled(boolean discover) {
        rpc("skills/read", params("threadId", thread), result -> {
            installed = array(result, "skills"); selected = array(result, "selected"); sources = array(result, "sources");
            if (result.optBoolean("catalogFrozenForActiveTurn")) {
                installedMode = true; renderLibrary(); status("A task is running; the current skill catalog remains fixed.");
            } else {
                renderLibrary();
                if (discover && !catalogLoaded) loadCatalog();
            }
        });
    }

    private void loadCatalog() {
        rpc("skills/community/catalog", params("sourceId", sourceId), result -> {
            catalogSource = result.optJSONObject("source");
            if (catalogSource == null) catalogSource = new JSONObject();
            catalog = array(result, "skills");
            for (int i = 0; i < catalog.length(); i++) {
                try { catalog.getJSONObject(i).put("repo", catalogSource.optString("repo")); }
                catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
            }
            catalogLoaded = true; renderLibrary();
        });
    }

    private TextView action(String label, Runnable run) {
        TextView view = ui.text(label, 13, true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(ui.dp(44));
        view.setPadding(ui.dp(4), 0, ui.dp(8), 0);
        view.setOnClickListener(v -> run.run());
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(ui.theme.border), null, null));
        return view;
    }

    private void renderLibrary() {
        detailName = null;
        confirmUninstall = false;
        sourceForm = false;
        body.removeAllViews();
        EditText search = new EditText(getContext());
        search.setSingleLine(true); search.setTextSize(13);
        search.setTextColor(ui.theme.ink); search.setHintTextColor(ui.theme.muted);
        search.setHint("Search skills"); search.setContentDescription("Search skills");
        search.setBackground(ui.theme.rounded(getContext(), ui.theme.raised, 12, false));
        search.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        search.getCompoundDrawables()[0].setTint(ui.theme.muted);
        android.graphics.drawable.Drawable searchIcon = search.getCompoundDrawables()[0];
        searchIcon.setBounds(0, 0, ui.dp(18), ui.dp(18));
        search.setCompoundDrawables(searchIcon, null, null, null);
        search.setCompoundDrawablePadding(ui.dp(8));
        search.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        search.setMinHeight(0); search.setMinimumHeight(0);
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString(); renderRows(); }
            public void afterTextChanged(Editable value) {}
        });
        body.addView(search, new LayoutParams(-1, ui.dp(44)));
        LinearLayout modes = new LinearLayout(getContext());
        chip(modes, "Discover", !installedMode, () -> { installedMode = false; renderLibrary(); if (!catalogLoaded) loadCatalog(); });
        chip(modes, "Installed", installedMode, () -> { installedMode = true; renderLibrary(); });
        ui.add(body, modes, 4);
        body.addView(status);
        rows = ui.column(); ui.add(body, rows, 6); renderRows();
    }

    private void chip(LinearLayout parent, String label, boolean active, Runnable run) {
        TextView chip = ui.text(label, 13, active);
        chip.setGravity(Gravity.CENTER); chip.setMinHeight(ui.dp(44));
        chip.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        chip.setTextColor(active ? ui.theme.ink : ui.theme.muted);
        chip.setBackground(ui.theme.rounded(getContext(), active ? ui.theme.raised : ui.theme.surface, 22, false));
        chip.setOnClickListener(v -> run.run());
        LayoutParams layout = new LayoutParams(-2, -2); layout.rightMargin = ui.dp(6);
        parent.addView(chip, layout);
    }

    private JSONObject localVersion(JSONObject entry) {
        for (int i = 0; i < installed.length(); i++) {
            JSONObject skill = installed.optJSONObject(i);
            if (skill == null || !skill.optString("name").equals(entry.optString("name"))) continue;
            JSONObject provenance = skill.optJSONObject("provenance");
            if (!entry.has("sourceId") || (provenance != null && provenance.optString("repo").equals(entry.optString("repo")))) return skill;
        }
        return null;
    }

    private void renderRows() {
        if (rows == null) return;
        rows.removeAllViews();
        JSONArray entries = installedMode ? installed : catalog;
        List<JSONObject> visible = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject skill = entries.optJSONObject(i);
            if (skill != null && SkillPresentation.matches(skill, query, category)) visible.add(skill);
        }
        ui.add(rows, ui.text((installedMode ? "My skills" : catalogSource.optString("title", "Community skills")) + " · " + visible.size()
                + (category.equals("All") ? "" : " · " + category), 12, false), 0);
        if (visible.isEmpty()) {
            ui.add(rows, ui.text(busy ? "Reading community…" : "No skills found. Try different keywords or sources.", 14, false), 18);
            return;
        }
        LinearLayout group = ui.column();
        group.setBackground(ui.theme.rounded(getContext(), ui.theme.raised, 14, false));
        for (int i = 0; i < visible.size(); i++) {
            if (i > 0) {
                View divider = new View(getContext()); divider.setBackgroundColor(ui.theme.border);
                LayoutParams line = new LayoutParams(-1, ui.dp(1)); line.leftMargin = ui.dp(54); line.rightMargin = ui.dp(12);
                group.addView(divider, line);
            }
            group.addView(skillRow(visible.get(i)));
        }
        ui.add(rows, group, 6);
        ui.add(rows, action("Refresh skill list", () -> { if (installedMode) loadInstalled(false); else loadCatalog(); }), 10);
    }

    private ImageView icon(JSONObject skill, int size) {
        String name = skill.optString("name");
        String path = skill.optString("iconPath");
        try {
            java.io.File file = new java.io.File(path);
            java.io.File allowed = new java.io.File(getContext().getFilesDir(), "runtime/harness/skill-icons");
            if (!path.isEmpty() && file.getCanonicalPath().startsWith(allowed.getCanonicalPath() + "/") && file.isFile()) {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);
                int pixels = ui.dp(size); options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > pixels * 2 || options.outHeight / options.inSampleSize > pixels * 2) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                android.graphics.Bitmap bitmap = BitmapFactory.decodeFile(path, options);
                if (bitmap != null) {
                    ImageView image = new ImageView(getContext()); image.setImageBitmap(bitmap);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    image.setBackground(ui.theme.rounded(getContext(), ui.theme.raised, size / 4, false));
                    image.setClipToOutline(true); image.setContentDescription("Original package icon " + name);
                    return image;
                }
            }
        } catch (java.io.IOException unavailable) { /* Keep the standard category icon. */ }
        String kind = SkillPresentation.category(name);
        int color = Color.parseColor(kind.equals("Creative") ? "#7864D9" : kind.equals("Development") ? "#3E87D9" : "#499780");
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(kind.equals("Creative") ? android.R.drawable.ic_menu_gallery : kind.equals("Development") ? android.R.drawable.ic_menu_manage : android.R.drawable.ic_menu_edit);
        int inset = size <= 32 ? 7 : 9;
        icon.setColorFilter(Color.WHITE); icon.setPadding(ui.dp(inset), ui.dp(inset), ui.dp(inset), ui.dp(inset));
        icon.setBackground(ui.theme.rounded(getContext(), color, size / 2, false));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private LinearLayout skillRow(JSONObject entry) {
        JSONObject local = localVersion(entry), copy = local == null ? entry : local;
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(12), ui.dp(8), ui.dp(10), ui.dp(8)); row.setMinimumHeight(ui.dp(64));
        row.setContentDescription("Skill entry " + entry.optString("name")); row.setOnClickListener(v -> openDetail(entry));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(ui.theme.border), null, null));
        row.addView(icon(copy.has("iconPath") ? copy : entry, 32), new LayoutParams(ui.dp(32), ui.dp(32)));
        LinearLayout copyColumn = ui.column();
        TextView title = ui.text(SkillPresentation.title(entry.optString("name")), 14, true);
        title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END); copyColumn.addView(title);
        TextView summary = ui.text(SkillPresentation.summary(copy), 11, false);
        summary.setMaxLines(1); summary.setEllipsize(TextUtils.TruncateAt.END); ui.add(copyColumn, summary, 3);
        LayoutParams content = new LayoutParams(0, -2, 1); content.leftMargin = ui.dp(10); row.addView(copyColumn, content);
        TextView state = ui.text(local == null ? "" : local.optBoolean("enabled") ? "Added" : "Disabled", 10, local != null);
        state.setPadding(ui.dp(8), 0, 0, 0); row.addView(state);
        return row;
    }

    private void openDetail(JSONObject entry) {
        ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(), 0);
        JSONObject local = localVersion(entry);
        if (local != null) { renderDetail(local); return; }
        renderDetail(entry);
        rpc("skills/community/detail", params("sourceId", entry.optString("sourceId"), "name", entry.optString("name"), "commit", entry.optString("commit")), detail -> {
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject candidate = catalog.optJSONObject(i);
                if (candidate != null && candidate.optString("name").equals(detail.optString("name"))) {
                    try { candidate.put("description", detail.optString("description")); }
                    catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
                }
            }
            if (detail.optString("name").equals(detailName)) renderDetail(detail);
        });
    }

    private boolean chosen(String path) {
        for (int i = 0; i < selected.length(); i++) {
            JSONObject skill = selected.optJSONObject(i);
            if (skill != null && path.equals(skill.optString("path"))) return true;
        }
        return false;
    }

    private void renderDetail(JSONObject skill) {
        if (!skill.optString("name").equals(detailName)) { confirmUninstall = false; updateCheck = null; }
        detailName = skill.optString("name");
        body.removeAllViews(); ui.add(body, action("Back to skills", this::renderLibrary), 0);
        LinearLayout identity = new LinearLayout(getContext()); identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.addView(icon(skill, 56), new LayoutParams(ui.dp(56), ui.dp(56)));
        LinearLayout names = ui.column();
        TextView title = ui.text(SkillPresentation.title(skill.optString("name")), 23, true); title.setTypeface(null, Typeface.BOLD);
        names.addView(title); ui.add(names, ui.text(skill.optString("name"), 12, false), 5);
        LayoutParams nameLayout = new LayoutParams(0, -2, 1); nameLayout.leftMargin = ui.dp(14); identity.addView(names, nameLayout);
        ui.add(body, identity, 22); ui.add(body, ui.text(SkillPresentation.summary(skill), 14, true), 22);
        ui.add(body, ui.text("Additional tools or accounts may need configuration. Installing a skill does not execute downloaded scripts.", 12, false), 14);
        if (skill.has("path")) installedControls(skill);
        else {
            Button install = ui.button("Install skill", true, () -> rpc("skills/community/install", params("sourceId", skill.optString("sourceId"), "name", skill.optString("name"), "commit", skill.optString("commit")), result -> {
                installed = array(result, "skills"); JSONObject local = localVersion(skill); if (local != null) renderDetail(local);
            }));
            install.setEnabled(skill.has("instructions")); ui.add(body, install, 22);
        }
        JSONObject origin = skill.optJSONObject("provenance"); if (origin == null) origin = skill;
        ui.add(body, ui.text("Source " + origin.optString("repo", "Runtime skill catalog"), 12, false), 22);
        TextView info = ui.text("Identifier " + skill.optString("name") + "\nCommit " + origin.optString("commit", "Managed by runtime") + "\nSHA-256 " + origin.optString("bodySha256", skill.optString("bodySha256")), 11, false);
        expandable("Provenance", info);
        expandable("Original description", ui.text(SkillPresentation.description(skill), 12, false));
        if (skill.has("instructions")) expandable("View original skill instructions", ui.text(skill.optString("instructions"), 12, false));
        body.addView(status);
    }

    private void expandable(String label, TextView content) {
        content.setTextIsSelectable(true); content.setVisibility(GONE);
        ui.add(body, action(label, () -> content.setVisibility(content.getVisibility() == GONE ? VISIBLE : GONE)), 10);
        ui.add(body, content, 6);
    }

    private void installedControls(JSONObject skill) {
        ui.add(body, ui.text(skill.optBoolean("nativeRecognized") ? "Recognized by runtime" : "Not yet recognized by runtime", 13, false), 20);
        JSONObject builtinUpdate = skill.optJSONObject("builtinUpdate");
        if (builtinUpdate != null) {
            String reason = builtinUpdate.optString("reason");
            String warning = switch (reason) {
                case "LOCAL_MODIFIED" -> "Your modified skill instructions were preserved and were not replaced by the package instructions.";
                case "UNVERIFIED_BASELINE" -> "Legacy official instructions could not be verified. Existing files were preserved without automatic update.";
                case "MISSING_FILE" -> "The skill directory has no instructions file. The existing directory was preserved without automatic reinstall.";
                case "UNSAFE_SKILL_PATH" -> "The skill file path changed. No automatic update was performed. Check the current directory.";
                case "INVALID_UPDATE_RECORD" -> "The previous update status could not be confirmed. Existing instructions were preserved.";
                default -> "Skill instructions were not automatically updated. Check the instructions file and provenance.";
            };
            TextView retained = ui.text(warning, 13, false);
            retained.setContentDescription(warning);
            ui.add(body, retained, 12);
        }
        JSONObject origin = skill.optJSONObject("provenance");
        boolean removable = origin != null && !"taku-app-development".equals(skill.optString("name"))
                && origin.optString("repo").matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
        if (removable) versionControls(skill);
        if (!skill.optBoolean("nativeRecognized")) {
            if (removable) uninstallControl(skill);
            return;
        }
        Switch enabled = new Switch(getContext()); enabled.setText("Enable skill"); enabled.setTextColor(ui.theme.ink);
        enabled.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.theme.accent, ui.theme.muted}));
        enabled.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.theme.border, ui.theme.raised}));
        enabled.setMinHeight(ui.dp(52)); enabled.setChecked(skill.optBoolean("enabled"));
        enabled.setOnClickListener(view -> {
            if (busy) { enabled.setChecked(skill.optBoolean("enabled")); return; }
            JSONObject request = params("path", skill.optString("path"));
            try { request.put("enabled", enabled.isChecked()); } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
            rpc("skills/set-enabled", request, result -> reloadDetail(skill.optString("path")));
        });
        ui.add(body, enabled, 10);
        if (skill.optBoolean("enabled") && !thread.isEmpty()) ui.add(body, ui.button(chosen(skill.optString("path")) ? "Stop loading in this conversation" : "Use in this conversation", true,
                () -> rpc("skills/select", params("threadId", thread, "path", skill.optString("path")), result -> reloadDetail(skill.optString("path")))), 14);
        if (removable) uninstallControl(skill);
    }

    private void versionControls(JSONObject skill) {
        String path = skill.optString("path");
        ui.add(body, ui.button("Check for updates", false, () -> rpc("skills/community/check-update", params("path", path), result -> {
            updateCheck = result; renderDetail(skill);
        })), 14);
        if (updateCheck != null) {
            boolean available = updateCheck.optBoolean("updateAvailable");
            ui.add(body, ui.text(available ? "New source commit found " + updateCheck.optString("latest") : "Already at the current source commit", 12, false), 8);
            if (available) ui.add(body, ui.button("Update skill", false, () -> rpc("skills/community/upgrade",
                    params("path", path, "commit", updateCheck.optString("latest")), result -> {
                        updateCheck = null; reloadDetail(path);
                    })), 8);
        }
        if (skill.optBoolean("rollbackAvailable")) ui.add(body, ui.button("Roll back to previous version", false, () -> rpc("skills/community/rollback",
                params("path", path), result -> { updateCheck = null; reloadDetail(path); })), 8);
        ui.add(body, ui.text("Rescan after switching versions; use the result next turn. Preserve the previous version for rollback.", 12, false), 8);
    }

    private void uninstallControl(JSONObject skill) {
        if (!confirmUninstall) {
            ui.add(body, ui.button("Uninstall skill", false, () -> { confirmUninstall = true; renderDetail(skill); }), 14);
            return;
        }
        ui.add(body, ui.text("Remove skill files and selections in all conversations, effective next turn. Existing conversation records are unchanged.", 12, false), 14);
        ui.add(body, ui.button("Confirm uninstall", false, () -> rpc("skills/community/uninstall",
                params("path", skill.optString("path")), result -> loadInstalled(false))), 10);
        ui.add(body, ui.button("Cancel", false, () -> { confirmUninstall = false; renderDetail(skill); }), 10);
    }

    private void reloadDetail(String path) {
        rpc("skills/read", params("threadId", thread), result -> {
            installed = array(result, "skills"); selected = array(result, "selected");
            for (int i = 0; i < installed.length(); i++) {
                JSONObject skill = installed.optJSONObject(i);
                if (skill != null && path.equals(skill.optString("path"))) {
                    if (skill.optString("name").equals(detailName)) renderDetail(skill);
                    return;
                }
            }
            renderLibrary();
        });
    }

    void options(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        String[] categories = {"All", "Development", "Creative", "Productivity"};
        for (int i = 0; i < categories.length; i++) {
            menu.getMenu().add(1, i, i, "Category · " + categories[i]).setCheckable(true).setChecked(category.equals(categories[i]));
        }
        menu.getMenu().setGroupCheckable(1, true, true);
        menu.getMenu().add(2, 100, 100, "Manage sources");
        menu.getMenu().add(2, 101, 101, "Refresh skill list");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() < categories.length) { category = categories[item.getItemId()]; renderLibrary(); }
            else if (item.getItemId() == 100) anchor.post(() -> chooseSource(anchor));
            else if (installedMode) loadInstalled(false); else loadCatalog();
            return true;
        });
        menu.show();
    }

    private void chooseSource(View anchor) {
        if (busy) return;
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        for (int i = 0; i < sources.length(); i++) menu.getMenu().add(0, i, i, sources.optJSONObject(i).optString("title"));
        menu.getMenu().add(0, sources.length(), sources.length(), "Add or edit repository");
        menu.setOnMenuItemClickListener(item -> {
            int index = item.getItemId();
            if (index == sources.length()) { addSource(); return true; }
            sourceId = sources.optJSONObject(index).optString("id"); catalogLoaded = false; catalog = new JSONArray();
            query = ""; installedMode = false; renderLibrary(); loadCatalog();
            return true;
        });
        menu.show();
    }

    private void addSource() {
        body.removeAllViews(); sourceForm = true;
        ui.add(body, action("Back to skills", this::renderLibrary), 0);
        ui.add(body, ui.heading("Add or edit repository"), 16);
        LinearLayout form = ui.column(); form.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        EditText repo = new EditText(getContext()); repo.setSingleLine(true); repo.setHint("owner/repo"); repo.setContentDescription("Skill community repository"); form.addView(repo);
        EditText path = new EditText(getContext()); path.setSingleLine(true); path.setHint("Skill directory, such as skills"); path.setContentDescription("Skill community directory"); form.addView(path);
        repo.setTextColor(ui.theme.ink); repo.setHintTextColor(ui.theme.muted);
        path.setTextColor(ui.theme.ink); path.setHintTextColor(ui.theme.muted);
        Switch privateRepo = new Switch(getContext()); privateRepo.setText("Private repository"); privateRepo.setTextColor(ui.theme.ink);
        ui.add(form, privateRepo, 14);
        EditText token = new EditText(getContext()); token.setSingleLine(true); token.setHint("GitHub read-only token (can be set later)");
        token.setContentDescription("GitHub repository credentials"); token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setTextColor(ui.theme.ink); token.setHintTextColor(ui.theme.muted); token.setVisibility(GONE); ui.add(form, token, 8);
        privateRepo.setOnCheckedChangeListener((button, checked) -> token.setVisibility(checked ? VISIBLE : GONE));
        ui.add(body, form, 14);
        ui.add(body, ui.text("Credentials stay on the device. Use a token with repository read access.", 12, false), 12);
        ui.add(body, ui.button("Add source", true, () -> {
            String repositoryName=repo.getText().toString().trim(), folder=path.getText().toString().trim();
            if(!repositoryName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || folder.contains("..") || folder.startsWith("/") || !folder.matches("[A-Za-z0-9_./-]*")) {
                status("Enter owner/repo and a relative directory."); return;
            }
            JSONObject request=params("repo",repositoryName,"path",folder);
            try { request.put("private",privateRepo.isChecked()); } catch(JSONException impossible) { throw new IllegalArgumentException(impossible); }
            Runnable save=() -> rpc("skills/community/add", request, result -> loadInstalled(false));
            if(privateRepo.isChecked() && token.length()>0) {
                try {
                    JSONObject secret=new JSONObject().put("op","skillCredential").put("action","set").put("repo",repositoryName).put("token",token.getText().toString());
                    token.setText(""); busy=true; status("Saving credentials…");
                    NativeClient.async(secret,new NativeClient.Callback() {
                        public void ready(JSONObject result) { busy=false; save.run(); }
                        public void failed(String message) { busy=false; status(message); }
                    });
                } catch(JSONException impossible) { status("Could not save credentials"); }
            } else save.run();
        }), 16);
        ui.add(body, ui.button("Clear credentials for this repository", false, () -> {
            String repositoryName=repo.getText().toString().trim();
            try {
                JSONObject request=new JSONObject().put("op","skillCredential").put("action","remove").put("repo",repositoryName);
                NativeClient.async(request,new NativeClient.Callback() {
                    public void ready(JSONObject result) { token.setText(""); status("Repository credentials cleared"); }
                    public void failed(String message) { status(message); }
                });
            } catch(JSONException impossible) { status("Could not clear credentials"); }
        }), 10);
        body.addView(status);
    }
}
