package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * JPEG.CAM Controller: Settings Menu
 *
 * Owns all state, views, navigation logic and rendering for the 8-page
 * settings menu system. Extracted from MainActivity as part of the God
 * Class decomposition.
 *
 * Architecture:
 *   - Builds and owns the menuContainer view tree in its constructor
 *   - Exposes open()/close() and directional navigation methods
 *   - Uses HostCallback for cross-cutting concerns (preferences, hardware)
 *   - CHARSET is public so HudController can reference it for naming mode
 */
public class MenuController {

    /** Character set used for on-camera name entry (menu AND HUD naming modes). */
    public static final String CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_";

    // -----------------------------------------------------------------------
    // Host callback
    // -----------------------------------------------------------------------
    public interface HostCallback {
        RecipeManager      getRecipeManager();
        ConnectivityManager getConnectivityManager();
        MatrixManager      getMatrixManager();
        Camera             getCamera();          // may be null if camera not open
        String             getAppVersion();

        // Preferences — read
        boolean isPrefFocusMeter();
        boolean isPrefCinemaMattes();
        boolean isPrefGridLines();
        int     getPrefJpegQuality();

        // Preferences — write
        void setPrefFocusMeter(boolean v);
        void setPrefCinemaMattes(boolean v);
        void setPrefGridLines(boolean v);
        void setPrefJpegQuality(int v);

        // UI coordination
        FrameLayout getMainUIContainer();
        int         getDisplayState();
        void        closeHud();          // hide HUD overlays when menu closes over them
        void        onMenuOpened();      // refreshRecipes + any pre-menu setup
        void        onMenuClosed();      // save prefs, triggerLutPreload, applyHardwareRecipe,
                                        // syncHardwareState, updateMainHUD
        void        onLutPreloadNeeded();
        void        scheduleHardwareApply();
        void        onHudModeRequested(int mode);
        void        onSetAutoPowerOffMode(boolean on);
        void        restoreFocusMode(String savedMode);
    }

    // -----------------------------------------------------------------------
    // Owned state
    // -----------------------------------------------------------------------
    private boolean  isOpen            = false;
    private boolean  isEditing         = false;
    private boolean  isNaming          = false;
    private boolean  isConfirmingDelete = false;
    private int      currentMainTab    = 0;
    private int      currentPage       = 1;
    private int      selection         = 0;
    private int      itemCount         = 0;
    private String   savedFocusMode    = null;
    private String   hotspotStatus     = "Press ENTER";
    private String   wifiStatus        = "Press ENTER";

    // Shared name buffer — also used by HudController for matrix / vault naming
    private char[]   nameBuffer        = "CUSTOM      ".toCharArray();
    private int      nameCursorPos     = 0;

    // -----------------------------------------------------------------------
    // Owned views
    // -----------------------------------------------------------------------
    private final LinearLayout   container;
    private final LinearLayout[] rows   = new LinearLayout[8];
    private final TextView[]     labels = new TextView[8];
    private final TextView[]     values = new TextView[8];
    private final TextView       tvTabRTL, tvTabSettings, tvTabNetwork, tvTabSupport;
    private final TextView       tvSubtitle;
    private final LinearLayout   supportContainer;

    private final HostCallback host;

    // -----------------------------------------------------------------------
    // Constructor — builds the full menu view tree
    // -----------------------------------------------------------------------
    public MenuController(Context ctx, FrameLayout rootLayout, HostCallback host) {
        this.host = host;

        container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.argb(250, 15, 15, 15));
        container.setPadding(20, 20, 20, 20);

        // Tab header row
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER);
        tabRow.setPadding(0, 0, 0, 10);
        tvTabRTL      = makeTabHeader(ctx, "RECIPES");
        tvTabSettings = makeTabHeader(ctx, "SETTINGS");
        tvTabNetwork  = makeTabHeader(ctx, "NETWORK");
        tvTabSupport  = makeTabHeader(ctx, "SUPPORT");
        tabRow.addView(tvTabRTL);
        tabRow.addView(tvTabSettings);
        tabRow.addView(tvTabNetwork);
        tabRow.addView(tvTabSupport);
        container.addView(tabRow);

        // Support tab content (hidden by default)
        supportContainer = new LinearLayout(ctx);
        supportContainer.setOrientation(LinearLayout.VERTICAL);
        supportContainer.setGravity(Gravity.CENTER);
        supportContainer.setVisibility(View.GONE);
        TextView tvTitle = new TextView(ctx); tvTitle.setText("JPEG.CAM");
        tvTitle.setTextColor(Color.WHITE); tvTitle.setTextSize(28);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        supportContainer.addView(tvTitle);
        TextView tvSub = new TextView(ctx);
        tvSub.setText("by JPEG Cookbook \u2022 v" + host.getAppVersion());
        tvSub.setTextColor(Color.LTGRAY); tvSub.setTextSize(12);
        tvSub.setPadding(0, 0, 0, 20);
        supportContainer.addView(tvSub);
        ImageView qrView = new ImageView(ctx);
        qrView.setImageResource(R.drawable.qr_hub);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(10, 10, 10, 10);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(240, 240);
        qrLp.setMargins(0, 0, 0, 20); qrView.setLayoutParams(qrLp);
        supportContainer.addView(qrView);
        TextView tvDesc = new TextView(ctx);
        tvDesc.setText("Manuals, Lens Profiles, & Support");
        tvDesc.setTextColor(Color.GRAY); tvDesc.setTextSize(12);
        supportContainer.addView(tvDesc);
        container.addView(supportContainer, new LinearLayout.LayoutParams(-1, -1));

        // Page subtitle
        tvSubtitle = new TextView(ctx);
        tvSubtitle.setTextSize(18);
        tvSubtitle.setTextColor(Color.WHITE);
        tvSubtitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvSubtitle.setPadding(10, 0, 0, 15);
        container.addView(tvSubtitle);

        // Divider
        View div = new View(ctx);
        div.setBackgroundColor(Color.GRAY);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, 2);
        divLp.setMargins(0, 0, 0, 15);
        container.addView(div, divLp);

        // 8 content rows
        for (int i = 0; i < 8; i++) {
            rows[i] = new LinearLayout(ctx);
            rows[i].setOrientation(LinearLayout.HORIZONTAL);
            rows[i].setGravity(Gravity.CENTER_VERTICAL);
            rows[i].setPadding(10, 0, 10, 0);
            container.addView(rows[i], new LinearLayout.LayoutParams(-1, 0, 1.0f));
            labels[i] = new TextView(ctx); labels[i].setTextSize(18); labels[i].setTypeface(Typeface.DEFAULT_BOLD);
            values[i] = new TextView(ctx); values[i].setTextSize(18); values[i].setGravity(Gravity.RIGHT);
            rows[i].addView(labels[i], new LinearLayout.LayoutParams(0, -2, 1.0f));
            rows[i].addView(values[i], new LinearLayout.LayoutParams(-2, -2));
            if (i < 7) {
                View rowDiv = new View(ctx);
                rowDiv.setBackgroundColor(Color.DKGRAY);
                container.addView(rowDiv, new LinearLayout.LayoutParams(-1, 1));
            }
        }

        container.setVisibility(View.GONE);
        rootLayout.addView(container, new FrameLayout.LayoutParams(-1, -1));
    }

    // -----------------------------------------------------------------------
    // Public state queries
    // -----------------------------------------------------------------------
    public boolean isOpen()           { return isOpen; }
    public boolean isNamingMode()     { return isNaming; }
    public boolean isEditingMode()    { return isEditing; }
    public boolean isConfirmingDelete(){ return isConfirmingDelete; }
    public char[]  getNameBuffer()    { return nameBuffer; }
    public int     getNameCursorPos() { return nameCursorPos; }
    public String  getSavedFocusMode(){ return savedFocusMode; }
    public int     getSelection()     { return selection; }
    public int     getCurrentPage()   { return currentPage; }
    public int     getCurrentMainTab(){ return currentMainTab; }
    public LinearLayout getContainer(){ return container; }

    // Setters for HUD enter-mode interactions
    public void setNamingMode(boolean v)       { isNaming = v; }
    public void setConfirmingDelete(boolean v) { isConfirmingDelete = v; }
    public void resetNameCursor()              { nameCursorPos = 0; }
    /** Fill the 12-char name buffer in-place from a string (pads/truncates). */
    public void fillNameBuffer(String src) {
        StringBuilder sb = new StringBuilder(src != null ? src : "");
        while (sb.length() < 12) sb.append(' ');
        String padded = sb.toString().substring(0, 12);
        for (int i = 0; i < 12; i++) nameBuffer[i] = padded.charAt(i);
    }
    /** Reset name buffer to "CUSTOM      " in-place. */
    public void resetNameBuffer() { fillNameBuffer("CUSTOM"); }
    /** Re-render the menu (e.g. after returning from a HUD overlay). */
    public void refreshDisplay()               { render(); }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Opens the menu: saves focus mode, locks to manual, resets nav state. */
    public void open() {
        Camera cam = host.getCamera();
        if (cam != null) {
            try {
                cam.cancelAutoFocus();
                Camera.Parameters p = cam.getParameters();
                savedFocusMode = p.getFocusMode();
                List<String> fModes = p.getSupportedFocusModes();
                if (fModes != null && fModes.contains("manual")) {
                    p.setFocusMode("manual");
                    cam.setParameters(p);
                }
            } catch (Exception ignored) {}
        }
        host.onMenuOpened();
        host.closeHud();
        isOpen      = true;
        isEditing   = false;
        isNaming    = false;
        currentMainTab = 0;
        currentPage    = 1;
        selection      = 0;
        container.setVisibility(View.VISIBLE);
        host.getMainUIContainer().setVisibility(View.GONE);
        render();
    }

    /** Closes the menu, restores camera state, fires post-menu host actions. */
    public void close() {
        isOpen             = false;
        isNaming           = false;
        isConfirmingDelete = false;
        host.closeHud();
        container.setVisibility(View.GONE);
        host.getMainUIContainer().setVisibility(
                host.getDisplayState() == 0 ? View.VISIBLE : View.GONE);
        host.restoreFocusMode(savedFocusMode);
        host.onMenuClosed();
    }

    // -----------------------------------------------------------------------
    // Directional navigation (called from MainActivity input handlers)
    // All return true if the menu consumed the event.
    // -----------------------------------------------------------------------

    public boolean handleUp() {
        if (!isOpen) return false;
        if (isNaming) { handleNamingChange(1); return true; }
        if (isEditing) { handleMenuChange(1); return true; }
        if (selection == 0)           selection = -2;
        else if (selection == -2)     { selection = itemCount - 1; if (selection < 0) selection = -2; }
        else                          selection--;
        render();
        return true;
    }

    public boolean handleDown() {
        if (!isOpen) return false;
        if (isNaming) { handleNamingChange(-1); return true; }
        if (isEditing) { handleMenuChange(-1); return true; }
        if (selection == -2)                    { selection = 0; if (itemCount == 0) selection = -2; }
        else if (selection == itemCount - 1)    selection = -2;
        else                                    selection++;
        render();
        return true;
    }

    public boolean handleLeft() {
        if (!isOpen) return false;
        if (selection == -2) {
            currentMainTab--;
            if (currentMainTab < 0) currentMainTab = 3;
            currentPage = tabToFirstPage(currentMainTab);
            render();
        } else if (isNaming) {
            nameCursorPos = Math.max(0, nameCursorPos - 1);
            render();
        } else if (isEditing) {
            handleMenuChange(-1);
        } else {
            currentPage--;
            if (currentPage < 1) currentPage = 8;
            currentMainTab = pageToTab(currentPage);
            selection = 0;
            render();
        }
        return true;
    }

    public boolean handleRight() {
        if (!isOpen) return false;
        if (selection == -2) {
            currentMainTab++;
            if (currentMainTab > 3) currentMainTab = 0;
            currentPage = tabToFirstPage(currentMainTab);
            render();
        } else if (isNaming) {
            nameCursorPos = Math.min(7, nameCursorPos + 1);
            render();
        } else if (isEditing) {
            handleMenuChange(1);
        } else {
            currentPage++;
            if (currentPage > 8) currentPage = 1;
            currentMainTab = pageToTab(currentPage);
            selection = 0;
            render();
        }
        return true;
    }

    public boolean handleDial(int dir) {
        if (!isOpen) return false;
        if (isNaming) { handleNamingChange(dir); return true; }
        if (isEditing) { handleMenuChange(dir); return true; }
        // Wheel scrolls item selection when not editing
        if (dir > 0) handleDown(); else handleUp();
        return true;
    }

    /** Advances the name buffer cursor — also called from HUD naming mode. */
    public void advanceNameCursor(int dir) {
        nameCursorPos += dir;
        if (nameCursorPos < 0) nameCursorPos = 0;
        if (nameCursorPos > 11) nameCursorPos = 11;
    }

    /** ENTER while menu is open: toggle editing, launch HUDs, or handle connection page. */
    public boolean handleEnter() {
        if (!isOpen) return false;
        if (currentPage == 7) { handleConnectionAction(); return true; }
        if (selection == -2) return true; // Tab level — enter does nothing
        if (selection < 0)   return true; // Subtitle row — enter does nothing
        isEditing = !isEditing;
        render();
        return true;
    }

    /** Launch HUD modes from menu — called from onEnterPressed page/selection dispatch. */
    public boolean dispatchHudLaunch() {
        if (!isOpen) return false;
        if (currentMainTab == 0 && currentPage == 1 && selection == 1) { host.onHudModeRequested(10); return true; }
        if (currentMainTab == 0 && currentPage == 1 && selection == 2) { host.onHudModeRequested(6);  return true; }
        if (currentMainTab == 0 && currentPage == 1 && selection == 3) { host.onHudModeRequested(3);  return true; }
        if (currentMainTab == 0 && currentPage == 1 && selection == 4) { host.onHudModeRequested(9);  return true; }
        if (currentMainTab == 0 && currentPage == 2 && selection == 0) { host.onHudModeRequested(2);  return true; }
        if (currentMainTab == 0 && currentPage == 2 && selection == 1) { host.onHudModeRequested(7);  return true; }
        if (currentMainTab == 0 && currentPage == 2 && selection == 2) { host.onHudModeRequested(1);  return true; }
        if (currentMainTab == 0 && currentPage == 2 && selection == 3) { host.onHudModeRequested(0);  return true; }
        if (currentMainTab == 0 && currentPage == 3 && selection == 0) { host.onHudModeRequested(8);  return true; }
        if (currentMainTab == 0 && currentPage == 3 && selection == 2) { host.onHudModeRequested(4);  return true; }
        if (currentMainTab == 0 && currentPage == 3 && selection == 1) {
            RTLProfile p = host.getRecipeManager().getCurrentProfile();
            String eff = p.pictureEffect != null ? p.pictureEffect : "off";
            if ("toy-camera".equals(eff)||"soft-focus".equals(eff)||"hdr-art".equals(eff)
                    ||"illust".equals(eff)||"watercolor".equals(eff)||"part-color".equals(eff)||"miniature".equals(eff)) {
                host.onHudModeRequested(5); return true;
            }
        }
        return false;
    }

    /** Status update from ConnectivityManager — routes to correct status string. */
    public void updateConnectionStatus(String target, String status) {
        if ("HOTSPOT".equals(target)) hotspotStatus = status;
        else wifiStatus = status;
        if (isOpen && currentPage == 7) render();
    }

    // -----------------------------------------------------------------------
    // Private — menu data change
    // -----------------------------------------------------------------------

    private void handleNamingChange(int dir) {
        RecipeManager rm = host.getRecipeManager();
        RTLProfile p = rm.getCurrentProfile();
        String name = p.profileName != null ? p.profileName : "";
        while (name.length() < 8) name += " ";
        if (name.length() > 8) name = name.substring(0, 8);
        char c = name.charAt(nameCursorPos);
        int idx = CHARSET.indexOf(c);
        if (idx == -1) idx = 0;
        idx = (idx + dir + CHARSET.length()) % CHARSET.length();
        p.profileName = name.substring(0, nameCursorPos) + CHARSET.charAt(idx) + name.substring(nameCursorPos + 1);
        render();
    }

    private void handleMenuChange(int dir) {
        RecipeManager rm = host.getRecipeManager();
        RTLProfile p = rm.getCurrentProfile();
        int sel = selection;

        if (currentPage == 1) {
            if (sel == 0 && !isNaming) {
                rm.savePreferences();
                rm.setCurrentSlot(Math.max(0, Math.min(9, rm.getCurrentSlot() + dir)));
                host.onLutPreloadNeeded();
            } else if (sel == 2) {
                String[] styles = {"Standard","Vivid","Neutral","Clear","Deep","Light","Portrait","Landscape","Sunset","Night Scene","Autumn Leaves","Black & White","Sepia"};
                int idx = 0; for (int i = 0; i < styles.length; i++) if (styles[i].equalsIgnoreCase(p.colorMode)) idx = i;
                p.colorMode = styles[(idx + dir + styles.length) % styles.length];
            } else if (sel == 4) {
                String[] dro = {"OFF","AUTO","LVL 1","LVL 2","LVL 3","LVL 4","LVL 5"};
                int idx = 0; for (int i = 0; i < dro.length; i++) if (dro[i].equalsIgnoreCase(p.dro)) idx = i;
                p.dro = dro[(idx + dir + dro.length) % dro.length];
            }
        } else if (currentPage == 2) {
            if (sel == 1) p.whiteBalance = cycleKelvin(p.whiteBalance, dir);
        } else if (currentPage == 3) {
            if (sel == 0) {
                String[] eff = {"off","toy-camera","pop-color","posterization","retro-photo","soft-high-key","partial-color","high-contrast-mono","soft-focus","hdr-painting","rich-tone-mono","miniature","watercolor","illustration"};
                int idx = 0; for (int i = 0; i < eff.length; i++) if (eff[i].equals(p.pictureEffect)) idx = i;
                p.pictureEffect = eff[(idx + dir + eff.length) % eff.length];
            } else if (sel == 2) p.softFocusLevel = Math.max(1, Math.min(3, p.softFocusLevel + dir));
        } else if (currentPage == 4) {
            if (sel == 0) { if (dir > 0 && p.lutIndex < rm.getRecipeNames().size()-1) p.lutIndex++; else if (dir < 0 && p.lutIndex > 0) p.lutIndex--; }
            else if (sel == 1 && p.lutIndex > 0) p.opacity = Math.max(10, Math.min(100, p.opacity + dir * 10));
            else if (sel == 2) p.grain = Math.max(0, Math.min(5, p.grain + dir));
            else if (sel == 3 && p.grain > 0) p.grainSize = Math.max(0, Math.min(2, p.grainSize + dir));
            else if (sel == 4 && p.grain > 0) p.advancedGrainExperimental = Math.max(0, Math.min(1, p.advancedGrainExperimental + dir));
            else if (sel == 5) p.vignette = Math.max(0, Math.min(5, p.vignette + dir));
        } else if (currentPage == 5) {
            if (sel == 0) p.rollOff        = Math.max(0, Math.min(5, p.rollOff + dir));
            else if (sel == 1) p.shadowToe = Math.max(0, Math.min(2, p.shadowToe + dir));
            else if (sel == 2) p.subtractiveSat = Math.max(0, Math.min(2, p.subtractiveSat + dir));
            else if (sel == 3) p.colorChrome = Math.max(0, Math.min(2, p.colorChrome + dir));
            else if (sel == 4) p.chromeBlue = Math.max(0, Math.min(2, p.chromeBlue + dir));
            else if (sel == 5) p.halation  = Math.max(0, Math.min(2, p.halation + dir));
            
            // NEW ROW ADDED HERE: Handles left/right d-pad clicks for Optical Bloom (0, 1, or 2)
            else if (sel == 6) p.bloom = Math.max(0, Math.min(2, p.bloom + dir));
            
        } else if (currentPage == 6) {
            if      (sel == 0) rm.setQualityIndex(Math.max(0, Math.min(2, rm.getQualityIndex() + dir)));
            else if (sel == 2) host.setPrefFocusMeter(!host.isPrefFocusMeter());
            else if (sel == 3) host.setPrefCinemaMattes(!host.isPrefCinemaMattes());
            else if (sel == 4) host.setPrefGridLines(!host.isPrefGridLines());
            else if (sel == 5) host.setPrefJpegQuality(Math.max(60, Math.min(100, host.getPrefJpegQuality() + dir * 5)));
        }

        render();
        rm.savePreferences();
        host.scheduleHardwareApply();
    }

    private void handleConnectionAction() {
        if (selection == 0) {
            hotspotStatus = "Starting...";
            if (host.getConnectivityManager() != null) { host.getConnectivityManager().startHotspot(); host.onSetAutoPowerOffMode(false); }
        } else if (selection == 1) {
            wifiStatus = "Connecting...";
            if (host.getConnectivityManager() != null) { host.getConnectivityManager().startHomeWifi(); host.onSetAutoPowerOffMode(false); }
        } else if (selection == 2) {
            hotspotStatus = "Press ENTER";
            wifiStatus    = "Press ENTER";
            if (host.getConnectivityManager() != null) { host.getConnectivityManager().stopNetworking(); host.onSetAutoPowerOffMode(true); }
        }
        render();
    }

    // -----------------------------------------------------------------------
    // Private — rendering
    // -----------------------------------------------------------------------

    private void render() {
        RecipeManager rm = host.getRecipeManager();
        RTLProfile p = rm.getCurrentProfile();

        // Tab highlight
        int orange = Color.rgb(227, 69, 20);
        tvTabRTL.setBackgroundColor     (selection == -2 && currentMainTab == 0 ? orange : Color.TRANSPARENT);
        tvTabSettings.setBackgroundColor(selection == -2 && currentMainTab == 1 ? orange : Color.TRANSPARENT);
        tvTabNetwork.setBackgroundColor (selection == -2 && currentMainTab == 2 ? orange : Color.TRANSPARENT);
        tvTabSupport.setBackgroundColor (selection == -2 && currentMainTab == 3 ? orange : Color.TRANSPARENT);
        tvTabRTL.setTextColor     (currentMainTab == 0 ? Color.WHITE : Color.GRAY);
        tvTabSettings.setTextColor(currentMainTab == 1 ? Color.WHITE : Color.GRAY);
        tvTabNetwork.setTextColor (currentMainTab == 2 ? Color.WHITE : Color.GRAY);
        tvTabSupport.setTextColor (currentMainTab == 3 ? Color.WHITE : Color.GRAY);

        // Subtitle
        tvSubtitle.setBackgroundColor(selection == -1 ? orange : Color.TRANSPARENT);
        String[] subtitles = {"","1. Recipe Identity & Base [HW]","2. Advanced Color Engine [HW]","3. Effects & Shading [HW]","4. LUTs & Textures [SW] - ADDS PROCESSING TIME","5. Analog Physics [SW] - ADDS PROCESSING TIME","Global Settings","Web Dashboard Server","Resources & Community"};
        if (currentPage >= 1 && currentPage <= 8) tvSubtitle.setText(subtitles[currentPage]);

        for (int i = 0; i < 8; i++) rows[i].setVisibility(View.GONE);
        supportContainer.setVisibility(View.GONE);

        if (currentPage == 8) { supportContainer.setVisibility(View.VISIBLE); itemCount = 0; return; }

        String scn = "UNKNOWN";
        Camera cam = host.getCamera();
        if (cam != null) { try { scn = cam.getParameters().getSceneMode().toUpperCase(); } catch (Exception ignored) {} }

        String[] amtLbls  = {"OFF","LOW","MED","HIGH","V.HIGH","MAX"};
        String[] sizeLbls = {"SMALL","MED","LARGE"};
        int ic = 0;

        if (currentMainTab == 0) {
            if (currentPage == 1) {
                ic = 5;
                String raw = p.profileName != null ? p.profileName : "";
                while (raw.length() < 8) raw += " ";
                if (raw.length() > 8) raw = raw.substring(0, 8);
                String dispName = raw;
                if (isNaming && selection == 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 8; i++) {
                        char c = raw.charAt(i);
                        String cs = (c == ' ') ? "&nbsp;" : String.valueOf(c);
                        if (i == nameCursorPos) sb.append("<font color='#00FFFF'><u>").append(cs).append("</u></font>");
                        else sb.append(cs);
                    }
                    dispName = sb.toString();
                }
                String fnd = "[ " + (p.colorMode != null ? p.colorMode : "STD").toUpperCase() + " | M-CON " + String.format("%+d", p.sharpnessGain) + " ]";
                String ts  = String.format("[ %+d,  %+d,  %+d ]", p.contrast, p.saturation, p.sharpness);
                String activeName = (p.profileName != null && !p.profileName.isEmpty()) ? p.profileName : "UNNAMED";
                setRow(0, "Recipe Slot (1-10)",  String.valueOf(rm.getCurrentSlot() + 1));
                setRow(1, "Recipe Manager",      "< " + activeName + " >");
                setRow(2, "Foundation Base",       fnd);
                setRow(3, "Tone & Style",          ts);
                setRow(4, "DRO (Dynamic Range)",   p.dro != null ? p.dro.toUpperCase() : "OFF");
            } else if (currentPage == 2) {
                ic = 4;
                String ab = p.wbShift == 0 ? "0" : (p.wbShift < 0 ? "B"+Math.abs(p.wbShift) : "A"+p.wbShift);
                String gm = p.wbShiftGM == 0 ? "0" : (p.wbShiftGM < 0 ? "M"+Math.abs(p.wbShiftGM) : "G"+p.wbShiftGM);
                boolean sixStd = p.colorDepthRed==0&&p.colorDepthGreen==0&&p.colorDepthBlue==0&&p.colorDepthCyan==0&&p.colorDepthMagenta==0&&p.colorDepthYellow==0;
                boolean mtxStd = p.advMatrix[0]==100&&p.advMatrix[1]==0&&p.advMatrix[2]==0&&p.advMatrix[3]==0&&p.advMatrix[4]==100&&p.advMatrix[5]==0&&p.advMatrix[6]==0&&p.advMatrix[7]==0&&p.advMatrix[8]==100;
                String mtxStr  = mtxStd ? "[ STANDARD ]" : "[ " + getActiveMatrixName(rm.getCurrentProfile(), host.getMatrixManager()) + " ]";
                setRow(0, "White Balance Shift", "[ " + ab + ", " + gm + " ]");
                setRow(1, "White Balance",        (p.whiteBalance != null ? p.whiteBalance : "Auto").toUpperCase());
                setRow(2, "6-Axis Color Depths",  sixStd ? "[ STANDARD ]" : "[ CUSTOM ]");
                setRow(3, "BIONZ RGB Matrix",     mtxStr);
            } else if (currentPage == 3) {
                ic = 3;
                String eff = p.pictureEffect != null ? p.pictureEffect : "off";
                String param = buildEffectParam(p, eff);
                String shade = "[ R " + String.format("%+d", p.shadingRed) + " | B " + String.format("%+d", p.shadingBlue) + " ]";
                setRow(0, "Picture Effect Base", eff.toUpperCase());
                setRow(1, "Effect Tweaker",       param);
                setRow(2, "Edge Shading Editor",  shade);
            } else if (currentPage == 4) {
                ic = 6;
                String[] engineLbls = {"LEGACY", "ADVANCED"};
                setRow(0, "LUT File",    rm.getRecipeNames().get(p.lutIndex));
                setRow(1, "LUT Opacity", p.opacity + "%");
                setRow(2, "Grain Amount",amtLbls[Math.max(0,Math.min(5,p.grain))]);
                setRow(3, "Grain Size",  sizeLbls[Math.max(0,Math.min(2,p.grainSize))]);
                setRow(4, "Grain Engine",engineLbls[Math.max(0,Math.min(1,p.advancedGrainExperimental))]);
                setRow(5, "Vignette",    amtLbls[Math.max(0,Math.min(5,p.vignette))]);
            } else if (currentPage == 5) {
                ic = 7; // CHANGED TO 7
                setRow(0, "Highlight Roll-Off",    amtLbls[Math.max(0,Math.min(5,p.rollOff))]);
                setRow(1, "Shadow Roll-Off (Toe)",  p.shadowToe==0?"OFF":(p.shadowToe==1?"WEAK":"FILMIC"));
                setRow(2, "Subtractive Sat",        p.subtractiveSat==0?"OFF":(p.subtractiveSat==1?"WEAK":"HEAVY"));
                setRow(3, "Color Chrome",           p.colorChrome==0?"OFF":(p.colorChrome==1?"WEAK":"STRONG"));
                setRow(4, "Chrome Blue",            p.chromeBlue==0?"OFF":(p.chromeBlue==1?"WEAK":"STRONG"));
                setRow(5, "Halation (Red Glow)",    p.halation==0?"OFF":(p.halation==1?"WEAK":"STRONG"));
                
                // NEW ROW ADDED HERE
                setRow(6, "Diffusion",              p.bloom==0?"OFF":(p.bloom==1?"1/4":"1/2"));
            }
        }
        if (currentPage == 6) {
            ic = 6;
            String[] qLbls = {"1/4 RES","HALF RES","FULL RES"};
            setRow(0, "SW Global Resolution", qLbls[rm.getQualityIndex()]);
            setRow(1, "Base Scene",            scn);
            setRow(2, "Manual Focus Meter",    host.isPrefFocusMeter()   ? "ON" : "OFF");
            setRow(3, "Anamorphic Crop",       host.isPrefCinemaMattes() ? "ON" : "OFF");
            setRow(4, "Rule of Thirds Grid",   host.isPrefGridLines()    ? "ON" : "OFF");
            setRow(5, "SW JPEG Quality",       String.valueOf(host.getPrefJpegQuality()));
        } else if (currentPage == 7) {
            ic = 3;
            setRow(0, "Camera Hotspot", hotspotStatus);
            setRow(1, "Home Wi-Fi",     wifiStatus);
            setRow(2, "Stop Networking","");
        }

        // Row highlight pass
        for (int i = 0; i < ic; i++) {
            boolean active = isRowActive(p, i);
            String plain = labels[i].getText().toString().replace("> ", "").replace("  ", "");
            if (i == selection) {
                labels[i].setText("> " + plain);
                if (!active) { rows[i].setBackgroundColor(Color.TRANSPARENT); labels[i].setTextColor(Color.DKGRAY); values[i].setTextColor(Color.DKGRAY); }
                else if (isEditing || isNaming) { rows[i].setBackgroundColor(Color.TRANSPARENT); labels[i].setTextColor(Color.WHITE); values[i].setTextColor(orange); }
                else { rows[i].setBackgroundColor(orange); labels[i].setTextColor(Color.WHITE); values[i].setTextColor(Color.WHITE); }
            } else {
                labels[i].setText("  " + plain);
                rows[i].setBackgroundColor(Color.TRANSPARENT);
                labels[i].setTextColor(active ? Color.WHITE : Color.DKGRAY);
                values[i].setTextColor (active ? Color.WHITE : Color.DKGRAY);
            }
        }
        itemCount = ic;
    }

    private void setRow(int i, String label, String value) {
        labels[i].setText(label);
        values[i].setText(value);
        rows[i].setVisibility(View.VISIBLE);
    }

    private boolean isRowActive(RTLProfile p, int i) {
        if (currentMainTab == 0 && currentPage == 3 && i == 1) {
            String eff = p.pictureEffect != null ? p.pictureEffect : "off";
            return "toy-camera".equals(eff)||"soft-focus".equals(eff)||"hdr-art".equals(eff)
                    ||"illust".equals(eff)||"watercolor".equals(eff)||"part-color".equals(eff)||"miniature".equals(eff);
        }
        if (currentMainTab == 0 && currentPage == 4) {
            if (i == 1) return p.lutIndex > 0;
            if (i == 3 || i == 4) return p.grain > 0;
        }
        return true;
    }

    private String buildEffectParam(RTLProfile p, String eff) {
        String tone = p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "NORM";
        if ("toy-camera".equals(eff)) {
            if ("NORMAL".equals(tone)) tone = "NORM"; else if ("MAGENTA".equals(tone)) tone = "MAG";
            return "[ " + tone + " | " + String.format("%+d", p.vignetteHardware) + " ]";
        } else if ("soft-focus".equals(eff)||"hdr-art".equals(eff)||"illust".equals(eff)||"watercolor".equals(eff)) {
            return "[ LVL: " + p.softFocusLevel + " ]";
        } else if ("part-color".equals(eff)) {
            return "[ COLOR: " + (p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "RED") + " ]";
        } else if ("miniature".equals(eff)) {
            return "[ AREA: " + (p.peToyCameraTone != null ? p.peToyCameraTone.toUpperCase() : "AUTO") + " ]";
        }
        return "N/A";
    }

    private String getActiveMatrixName(RTLProfile p, MatrixManager mm) {
        if (mm == null || mm.getCount() == 0) return "CUSTOM";
        for (int f = 0; f < mm.getCount(); f++) {
            int[] loaded = mm.getValues(f);
            boolean match = true;
            for (int i = 0; i < 9; i++) if (p.advMatrix[i] != loaded[i]) { match = false; break; }
            if (match) return mm.getNames().get(f);
        }
        return "CUSTOM";
    }

    public static String cycleKelvin(String current, int dir) {
        if (current == null) current = "Auto";
        List<String> list = new ArrayList<>();
        list.add("Auto");
        for (int i = 2500; i <= 9900; i += 100) list.add(i + "K");
        int idx = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).equalsIgnoreCase(current)) { idx = i; break; }
        return list.get((idx + dir + list.size()) % list.size());
    }

    private int tabToFirstPage(int tab) {
        if (tab == 0) return 1;
        if (tab == 1) return 6;
        if (tab == 2) return 7;
        return 8;
    }

    private int pageToTab(int page) {
        if (page <= 5) return 0;
        if (page == 6) return 1;
        if (page == 7) return 2;
        return 3;
    }

    private TextView makeTabHeader(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, 10);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        return tv;
    }
}
