package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Gravity;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * JPEG.CAM Controller: Heads-Up Display (HUD)
 *
 * Owns all state, views, and logic for the 11-mode parameter overlay system.
 * Extracted from MainActivity as part of the God Class decomposition (Phase 5).
 *
 * HUD Modes:
 *   0 - BIONZ RGB Color Matrix (9-cell grid + preset scrolling)
 *   1 - 6-Axis Color Depth
 *   2 - White Balance Grid (special: cursor-based, not cell-based)
 *   3 - Tone & Style (contrast / saturation / sharpness)
 *   4 - Edge Shading (lens shading correction)
 *   5 - Picture Effect Parameters
 *   6 - Foundation Base (creative style + micro-contrast)
 *   7 - White Balance (Kelvin temperature)
 *   8 - Picture Effect selector
 *   9 - DRO (Dynamic Range Optimizer)
 *  10 - Recipe Vault Manager
 */
public class HudController {

    // -----------------------------------------------------------------------
    // Host callback
    // -----------------------------------------------------------------------
    public interface HostCallback {
        RecipeManager    getRecipeManager();
        MatrixManager    getMatrixManager();
        MenuController   getMenuController();
        TextView         getTvTopStatus();
        Handler          getUiHandler();
        Typeface         getDigitalFont();
        void             onHudClosed();           // restore menu, re-render
        void             onLutPreloadNeeded();
        void             scheduleHardwareApply(); // delayed applyHardwareRecipe
        void             applyHardwareRecipeNow();
    }

    // -----------------------------------------------------------------------
    // Owned state
    // -----------------------------------------------------------------------
    private boolean  active              = false;
    private int      selection           = 0;
    private int      mode                = 0;
    private boolean  updatePending       = false;

    // Matrix scrolling helpers
    private boolean  isScrollingMatrices = false;
    private int      activeMatrixIndex   = 0;

    // Vault state
    private List<RecipeManager.VaultItem> vaultItems = new ArrayList<>();
    private int      vaultIndex          = 0;

    // -----------------------------------------------------------------------
    // Owned views
    // -----------------------------------------------------------------------
    private final LinearLayout   overlay;          // 9-cell row
    private final LinearLayout[] cells  = new LinearLayout[9];
    private final TextView[]     cellLabels = new TextView[9];
    private final TextView[]     cellValues = new TextView[9];
    private final TextView       tooltip;
    private final FrameLayout    wbGrid;
    private final View           wbCursor;
    private final TextView       wbValueText;

    private final HostCallback host;

    // -----------------------------------------------------------------------
    // Constructor — builds HUD view tree and injects into mainUIContainer
    // -----------------------------------------------------------------------
    public HudController(Context ctx, FrameLayout mainUIContainer, HostCallback host) {
        this.host = host;
        Typeface font = host.getDigitalFont();

        // 9-cell horizontal overlay (pinned to bottom)
        overlay = new LinearLayout(ctx);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setBackgroundColor(Color.argb(220, 15, 15, 15));
        overlay.setPadding(10, 15, 10, 15);
        overlay.setVisibility(View.GONE);
        for (int i = 0; i < 9; i++) {
            cells[i] = new LinearLayout(ctx);
            cells[i].setOrientation(LinearLayout.VERTICAL);
            cells[i].setGravity(Gravity.CENTER);
            cellLabels[i] = new TextView(ctx); cellLabels[i].setTextColor(Color.GRAY); cellLabels[i].setTextSize(14);
            if (font != null) cellLabels[i].setTypeface(font); else cellLabels[i].setTypeface(Typeface.DEFAULT_BOLD);
            cellValues[i] = new TextView(ctx); cellValues[i].setTextColor(Color.WHITE); cellValues[i].setTextSize(18);
            if (font != null) cellValues[i].setTypeface(font); else cellValues[i].setTypeface(Typeface.DEFAULT_BOLD);
            cells[i].addView(cellLabels[i]); cells[i].addView(cellValues[i]);
            overlay.addView(cells[i], new LinearLayout.LayoutParams(0, -2, 1.0f));
        }
        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        overlayLp.setMargins(0, 0, 0, 25);
        mainUIContainer.addView(overlay, overlayLp);

        // Tooltip text (above overlay)
        tooltip = new TextView(ctx);
        tooltip.setTextColor(Color.LTGRAY); tooltip.setTextSize(12);
        tooltip.setGravity(Gravity.CENTER); tooltip.setPadding(10, 8, 10, 8);
        tooltip.setBackgroundColor(Color.argb(200, 15, 15, 15));
        tooltip.setVisibility(View.GONE);
        FrameLayout.LayoutParams ttLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        ttLp.setMargins(0, 0, 0, 130);
        mainUIContainer.addView(tooltip, ttLp);

        // WB grid (mode 2 — special cursor UI)
        wbGrid = new FrameLayout(ctx);
        wbGrid.setBackgroundColor(Color.argb(160, 20, 20, 20));
        wbGrid.setVisibility(View.GONE);
        View vAxis = new View(ctx); vAxis.setBackgroundColor(Color.GRAY);
        wbGrid.addView(vAxis, new FrameLayout.LayoutParams(2, 280, Gravity.CENTER));
        View hAxis = new View(ctx); hAxis.setBackgroundColor(Color.GRAY);
        wbGrid.addView(hAxis, new FrameLayout.LayoutParams(280, 2, Gravity.CENTER));
        TextView lG = makeLabel(ctx,"G"); wbGrid.addView(lG, new FrameLayout.LayoutParams(-2,-2, Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        TextView lM = makeLabel(ctx,"M"); wbGrid.addView(lM, new FrameLayout.LayoutParams(-2,-2, Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        TextView lB = makeLabel(ctx,"B"); FrameLayout.LayoutParams pB = new FrameLayout.LayoutParams(-2,-2,Gravity.LEFT|Gravity.CENTER_VERTICAL); pB.setMargins(10,0,0,0); wbGrid.addView(lB, pB);
        TextView lA = makeLabel(ctx,"A"); FrameLayout.LayoutParams pA = new FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT|Gravity.CENTER_VERTICAL); pA.setMargins(0,0,10,0); wbGrid.addView(lA, pA);
        wbValueText = new TextView(ctx); wbValueText.setTextColor(Color.rgb(227,69,20)); wbValueText.setTextSize(16);
        if (font != null) wbValueText.setTypeface(font); else wbValueText.setTypeface(Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams pVal = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT); pVal.setMargins(0,10,15,0);
        wbGrid.addView(wbValueText, pVal);
        wbCursor = new View(ctx); wbCursor.setBackgroundColor(Color.rgb(227,69,20));
        FrameLayout.LayoutParams cursorLp = new FrameLayout.LayoutParams(14,14,Gravity.TOP|Gravity.LEFT); cursorLp.setMargins(153,153,0,0);
        wbGrid.addView(wbCursor, cursorLp);
        mainUIContainer.addView(wbGrid, new FrameLayout.LayoutParams(320,320,Gravity.CENTER));
    }

    private TextView makeLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx); tv.setText(text); tv.setTextColor(Color.WHITE); return tv;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------
    /** View accessors — used by MainActivity for visibility coordination. */
    public LinearLayout getOverlay()    { return overlay; }
    public FrameLayout  getWbGrid()     { return wbGrid; }
    public View         getWbCursor()   { return wbCursor; }
    public TextView     getWbValueText(){ return wbValueText; }
    public TextView     getTooltip()    { return tooltip; }

    public boolean isActive()    { return active; }
    public int     getMode()     { return mode; }
    public int     getSelection(){ return selection; }
    public void    setSelection(int sel) { selection = sel; }

    /** Public immediate refresh (for use from MainActivity enter/exit logic). */
    public void update()         { refresh(); }

    /** Vault accessors — used by MainActivity onEnterPressed mode-10 block. */
    public List<RecipeManager.VaultItem> getVaultItems() { return vaultItems; }
    public int  getVaultIndex()   { return vaultIndex; }
    public void setVaultIndex(int idx) { vaultIndex = idx; }
    public void refreshVaultItems() {
        vaultItems = host.getRecipeManager().getVaultItems();
        if (vaultIndex >= vaultItems.size() || vaultIndex < 0) vaultIndex = 0;
    }

    /** Open a HUD mode at selection 0. */
    public void launch(int hudMode)                     { launch(hudMode, 0); }

    /** Open a HUD mode at a specific default selection. */
    public void launch(int hudMode, int defaultSel) {
        active    = true;
        mode      = hudMode;
        selection = defaultSel;
        host.getMenuController().setConfirmingDelete(false);
        host.getMenuController().getContainer().setVisibility(View.GONE);
        if (hudMode == 2) {
            overlay.setVisibility(View.GONE);
            if (tooltip != null) tooltip.setVisibility(View.GONE);
            wbGrid.setVisibility(View.VISIBLE);
        } else {
            overlay.setVisibility(View.VISIBLE);
            wbGrid.setVisibility(View.GONE);
        }
        refresh();
    }

    /** Close HUD cleanly, restoring menu behind it. */
    public void close() {
        active = false;
        overlay.setVisibility(View.GONE);
        if (tooltip != null) tooltip.setVisibility(View.GONE);
        wbGrid.setVisibility(View.GONE);
        host.onHudClosed();
    }

    /** Hide all HUD overlays without triggering close callback (for updateMainHUD). */
    public void hideOverlays() {
        overlay.setVisibility(View.GONE);
        if (tooltip != null) tooltip.setVisibility(View.GONE);
        wbGrid.setVisibility(View.GONE);
    }

    /** Schedule a debounced refresh (100ms) — used from hardware state callbacks. */
    public void requestUpdate() {
        if (!updatePending) {
            updatePending = true;
            host.getUiHandler().postDelayed(new Runnable() {
                @Override public void run() { updatePending = false; refresh(); }
            }, 100);
        }
    }

    // -----------------------------------------------------------------------
    // Input handlers — return true if consumed
    // -----------------------------------------------------------------------
    public boolean handleUp() {
        if (!active) return false;
        if (mode == 2) { handleWbAdjustment(0, 1); return true; }
        selection--;
        int minIdx = (mode == 0) ? -1 : 0;
        if (selection < minIdx) {
            if      (mode == 0)                         selection = 8;
            else if (mode == 1)                         selection = 5;
            else if (mode == 3)                         selection = 2;
            else if (mode == 10)                        selection = 3;
            else if (mode == 4 || mode == 6)            selection = 1;
            else                                        selection = 0;
        }
        refresh(); return true;
    }

    public boolean handleDown() {
        if (!active) return false;
        if (mode == 2) { handleWbAdjustment(0, -1); return true; }
        selection++;
        int maxIdx = 0;
        if      (mode == 0)                         maxIdx = 8;
        else if (mode == 1)                         maxIdx = 5;
        else if (mode == 3)                         maxIdx = 2;
        else if (mode == 10)                        maxIdx = 3;
        else if (mode == 4 || mode == 6)            maxIdx = 1;
        if (selection > maxIdx) selection = (mode == 0) ? -1 : 0;
        refresh(); return true;
    }

    public boolean handleLeft() {
        if (!active) return false;
        if (mode == 2) { handleWbAdjustment(-1, 0); return true; }
        selection = Math.max(0, selection - 1); refresh(); return true;
    }

    public boolean handleRight() {
        if (!active) return false;
        if (mode == 2) { handleWbAdjustment(1, 0); return true; }
        int maxSlots = 0;
        if      (mode == 0)                                        maxSlots = 8;
        else if (mode == 1)                                        maxSlots = 5;
        else if (mode == 3)                                        maxSlots = 2;
        else if (mode == 10)                                       maxSlots = 3;
        else if (mode == 4||mode==5||mode==6||mode==8)             maxSlots = 1;
        selection = Math.min(maxSlots, selection + 1); refresh(); return true;
    }

    public boolean handleDial(int dir) {
        if (!active) return false;
        if (mode == 2) { handleWbAdjustment(dir, 0); return true; }
        handleAdjustment(dir); return true;
    }

    /** Save the current advMatrix to SD card under the given name. */
    public void saveCustomMatrix(String customName) {
        MatrixManager mm = host.getMatrixManager();
        if (mm == null) return;
        String finalName = customName;
        for (String existing : mm.getNames()) if (existing.equalsIgnoreCase(finalName)) finalName += "+";
        RTLProfile p = host.getRecipeManager().getCurrentProfile();
        mm.saveMatrix(finalName, p.advMatrix, "Saved directly from camera UI.");
        mm.scanMatrices();
        isScrollingMatrices = false;
        refresh();
    }

    // -----------------------------------------------------------------------
    // Private — data adjustment
    // -----------------------------------------------------------------------
    private void handleWbAdjustment(int dAb, int dGm) {
        RTLProfile p = host.getRecipeManager().getCurrentProfile();
        p.wbShift   = Math.max(-7, Math.min(7, p.wbShift   + dAb));
        p.wbShiftGM = Math.max(-7, Math.min(7, p.wbShiftGM + dGm));
        refresh();
        host.getUiHandler().removeCallbacksAndMessages(null);
        host.scheduleHardwareApply();
    }

    private void handleAdjustment(int dir) {
        RTLProfile p = host.getRecipeManager().getCurrentProfile();
        MatrixManager mm = host.getMatrixManager();

        if (mode == 0) {
            if (selection == -1) {
                if (mm != null && mm.getCount() > 0) {
                    isScrollingMatrices = true;
                    activeMatrixIndex = ((activeMatrixIndex + dir) % mm.getCount() + mm.getCount()) % mm.getCount();
                    int[] v = mm.getValues(activeMatrixIndex);
                    for (int i = 0; i < 9; i++) p.advMatrix[i] = v[i];
                }
            } else {
                isScrollingMatrices = false;
                p.advMatrix[selection] = Math.max(-200, Math.min(200, p.advMatrix[selection] + dir * 5));
            }
        } else if (mode == 1) {
            if      (selection == 0) p.colorDepthRed     = Math.max(-7,Math.min(7,p.colorDepthRed     + dir));
            else if (selection == 1) p.colorDepthGreen   = Math.max(-7,Math.min(7,p.colorDepthGreen   + dir));
            else if (selection == 2) p.colorDepthBlue    = Math.max(-7,Math.min(7,p.colorDepthBlue    + dir));
            else if (selection == 3) p.colorDepthCyan    = Math.max(-7,Math.min(7,p.colorDepthCyan    + dir));
            else if (selection == 4) p.colorDepthMagenta = Math.max(-7,Math.min(7,p.colorDepthMagenta + dir));
            else if (selection == 5) p.colorDepthYellow  = Math.max(-7,Math.min(7,p.colorDepthYellow  + dir));
        } else if (mode == 3) {
            if      (selection == 0) p.contrast   = Math.max(-3,Math.min(3,p.contrast   + dir));
            else if (selection == 1) p.saturation = Math.max(-3,Math.min(3,p.saturation + dir));
            else if (selection == 2) p.sharpness  = Math.max(-3,Math.min(3,p.sharpness  + dir));
        } else if (mode == 4) {
            if      (selection == 0) p.shadingRed  = Math.max(-7,Math.min(7,p.shadingRed  + dir));
            else if (selection == 1) p.shadingBlue = Math.max(-7,Math.min(7,p.shadingBlue + dir));
        } else if (mode == 5) {
            String eff = p.pictureEffect != null ? p.pictureEffect : "off";
            if (selection == 0) {
                if ("soft-focus".equals(eff)||"hdr-art".equals(eff)||"illust".equals(eff)||"watercolor".equals(eff)) {
                    p.softFocusLevel = Math.max(1,Math.min(3,p.softFocusLevel+dir));
                } else {
                    String[] opts = {"normal"};
                    if ("toy-camera".equals(eff))  opts = new String[]{"normal","cool","warm","green","magenta"};
                    else if ("part-color".equals(eff)) opts = new String[]{"red","green","blue","yellow"};
                    else if ("miniature".equals(eff))  opts = new String[]{"auto","left","vcenter","right","upper","hcenter","lower"};
                    if (opts.length > 1) { int idx=0; for(int i=0;i<opts.length;i++) if(opts[i].equals(p.peToyCameraTone)) idx=i; p.peToyCameraTone=opts[(idx+dir+opts.length)%opts.length]; }
                }
            } else if (selection == 1 && "toy-camera".equals(eff)) { p.vignetteHardware = Math.max(-16,Math.min(16,p.vignetteHardware+dir)); }
        } else if (mode == 6) {
            if (selection == 0) { String[] styles={"standard","vivid","portrait","landscape","mono","sunset","sepia"}; int idx=0; for(int i=0;i<styles.length;i++) if(styles[i].equalsIgnoreCase(p.colorMode)) idx=i; p.colorMode=styles[(idx+dir+styles.length)%styles.length]; }
            else if (selection == 1) p.sharpnessGain = Math.max(-50,Math.min(50,p.sharpnessGain + dir*5));
        } else if (mode == 7) { p.whiteBalance = MenuController.cycleKelvin(p.whiteBalance, dir);
        } else if (mode == 8) {
            if (selection == 0) { String[] eff={"off","toy-camera","pop-color","posterization","retro-photo","soft-high-key","part-color","rough-mono","soft-focus","hdr-art","richtone-mono","miniature","watercolor","illust"}; int idx=0; for(int i=0;i<eff.length;i++) if(eff[i].equals(p.pictureEffect)) idx=i; p.pictureEffect=eff[(idx+dir+eff.length)%eff.length]; }
        } else if (mode == 9) {
            if (selection == 0) { String[] dro={"OFF","AUTO","LVL 1","LVL 2","LVL 3","LVL 4","LVL 5"}; int idx=0; for(int i=0;i<dro.length;i++) if(dro[i].equalsIgnoreCase(p.dro)) idx=i; p.dro=dro[(idx+dir+dro.length)%dro.length]; }
        } else if (mode == 10) {
            if (selection == 1 && !vaultItems.isEmpty() && !vaultItems.get(0).filename.equals("NONE")) {
                vaultIndex = (vaultIndex + dir + vaultItems.size()) % vaultItems.size();
                host.getRecipeManager().previewVaultToSlot(vaultItems.get(vaultIndex).filename);
                host.onLutPreloadNeeded();
            }
        }
        refresh();
        host.scheduleHardwareApply();
    }

    // -----------------------------------------------------------------------
    // Private — rendering
    // -----------------------------------------------------------------------
    private void refresh() {
        RTLProfile p   = host.getRecipeManager().getCurrentProfile();
        MatrixManager mm = host.getMatrixManager();
        TextView tvTop = host.getTvTopStatus();
        MenuController mc = host.getMenuController();
        String tip = "";
        int activeCells = 0;
        String[] labels = new String[9]; String[] values = new String[9];

        // --- MODE 2: WB GRID ---
        if (mode == 2) {
            int ab = p.wbShift; int gm = p.wbShiftGM;
            FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) wbCursor.getLayoutParams();
            cp.setMargins(153 + ab * 20, 153 - gm * 20, 0, 0);
            wbCursor.setLayoutParams(cp);
            String abStr = ab==0?"0":(ab<0?"B"+Math.abs(ab):"A"+ab);
            String gmStr = gm==0?"0":(gm<0?"M"+Math.abs(gm):"G"+gm);
            wbValueText.setText(abStr + ", " + gmStr);
            return;
        }

        if (mode == 0) {
            activeCells = 9;
            labels = new String[]{"R-R","G-R","B-R","R-G","G-G","B-G","R-B","G-B","B-B"};
            int rBal=p.advMatrix[0]+p.advMatrix[1]+p.advMatrix[2];
            int gBal=p.advMatrix[3]+p.advMatrix[4]+p.advMatrix[5];
            int bBal=p.advMatrix[6]+p.advMatrix[7]+p.advMatrix[8];
            String balText = String.format(" [ R:%d%% | G:%d%% | B:%d%% ]",rBal,gBal,bBal);
            String currentName="CUSTOM (UNSAVED)"; String matrixNote="Use D-Pad to cycle SD Card matrices.";
            if (mm != null && mm.getCount() > 0) {
                if (isScrollingMatrices) { currentName=mm.getNames().get(activeMatrixIndex); matrixNote=mm.getNote(activeMatrixIndex);
                } else { for(int f=0;f<mm.getCount();f++){ int[] ld=mm.getValues(f); boolean m=true; for(int i=0;i<9;i++) if(p.advMatrix[i]!=ld[i]){m=false;break;} if(m){activeMatrixIndex=f;currentName=mm.getNames().get(f);matrixNote=mm.getNote(f);break;} } }
            }
            if (tvTop != null) {
                if (mc.isNamingMode()) {
                    StringBuilder sb=new StringBuilder("NAME: "); char[] buf=mc.getNameBuffer(); int pos=mc.getNameCursorPos();
                    for(int i=0;i<buf.length;i++) { if(i==pos) sb.append("[").append(buf[i]).append("]"); else sb.append(buf[i]); }
                    tvTop.setText(sb.toString()); tvTop.setTextColor(Color.YELLOW);
                } else { tvTop.setText("MATRIX: "+currentName); tvTop.setTextColor(selection==-1?Color.rgb(227,69,20):Color.WHITE); }
                tvTop.setVisibility(View.VISIBLE);
            }
            if (selection==-1) tip="FILE: "+matrixNote+"\n"+balText;
            else { String[] t={"Red sensitivity to real-world Red light (Primary - baseline is 100)","Pushes Green light into Red channel (baseline is 0)","Pushes Blue light into Red channel (baseline is 0)","Pushes Red light into Green channel (baseline is 0)","Green sensitivity to real-world Green light (Primary - baseline is 100)","Pushes Blue light into Green channel (baseline is 0)","Pushes Red light into Blue channel (baseline is 0)","Pushes Green light into Blue channel (baseline is 0)","Blue sensitivity to real-world Blue light (Primary - baseline is 100)."}; if(selection>=0&&selection<t.length) tip=t[selection]+"\n"+balText; }
            for (int i=0;i<9;i++) values[i]=p.advMatrix[i]+"%";

        } else if (mode == 1) {
            activeCells=6; labels=new String[]{"RED","GRN","BLU","CYN","MAG","YEL"};
            int[] d={p.colorDepthRed,p.colorDepthGreen,p.colorDepthBlue,p.colorDepthCyan,p.colorDepthMagenta,p.colorDepthYellow};
            for(int i=0;i<6;i++) values[i]=d[i]==0?"0":String.format("%+d",d[i]);
            tip="Alters the luminance and depth of the target color phase";
        } else if (mode == 3) {
            activeCells=3; labels=new String[]{"CONTRAST","SATURATION","SHARPNESS"};
            int[] v={p.contrast,p.saturation,p.sharpness}; for(int i=0;i<3;i++) values[i]=v[i]==0?"0":String.format("%+d",v[i]);
            if(selection==2) tip="Standard hardware sharpness (Micro-Contrast is stronger)";
        } else if (mode == 4) {
            activeCells=2; labels=new String[]{"SHADE RED","SHADE BLUE"};
            values[0]=p.shadingRed==0?"0":String.format("%+d",p.shadingRed); values[1]=p.shadingBlue==0?"0":String.format("%+d",p.shadingBlue);
            tip="Injects color shifts into the corners to simulate vintage lens tinting";
        } else if (mode == 5) {
            String eff=p.pictureEffect!=null?p.pictureEffect:"off"; String g=p.peToyCameraTone!=null?p.peToyCameraTone.toUpperCase():"NORM";
            if("toy-camera".equals(eff)){activeCells=2;labels=new String[]{"TOY-TONE","HW-VIGNETTE"};values[0]=g.equals("NORMAL")?"NORM":(g.equals("MAGENTA")?"MAG":g);values[1]=p.vignetteHardware==0?"0":String.format("%+d",p.vignetteHardware);}
            else if("soft-focus".equals(eff)||"hdr-art".equals(eff)||"illust".equals(eff)||"watercolor".equals(eff)){activeCells=1;labels=new String[]{"LEVEL"};values[0]=String.valueOf(p.softFocusLevel);}
            else if("part-color".equals(eff)){activeCells=1;labels=new String[]{"COLOR"};values[0]=g.equals("NORMAL")?"RED":g;}
            else if("miniature".equals(eff)){activeCells=1;labels=new String[]{"AREA"};values[0]=g.equals("NORMAL")?"AUTO":g;}
            else{activeCells=1;labels=new String[]{"EFFECT"};values[0]="NO PARAMS";}
        } else if (mode == 6) {
            activeCells=2; labels=new String[]{"STYLE","MICRO-CONTRAST"};
            values[0]=p.colorMode!=null?p.colorMode.toUpperCase():"STD"; values[1]=p.sharpnessGain==0?"0":String.format("%+d",p.sharpnessGain);
            if(selection==1) tip="Aggressive frequency enhancement (Affects film grain texture)";
        } else if (mode == 7) {
            activeCells=1;labels=new String[]{"WHITE BALANCE"};values[0]=p.whiteBalance!=null?p.whiteBalance.toUpperCase():"AUTO";
            tip="Adjust Kelvin Temperature (2500K - 9900K)";
        } else if (mode == 8) {
            activeCells=1;labels=new String[]{"EFFECT"};values[0]=p.pictureEffect!=null?p.pictureEffect.toUpperCase():"OFF";
        } else if (mode == 9) {
            activeCells=1;labels=new String[]{"DYNAMIC RANGE"};values[0]=p.dro!=null?p.dro.toUpperCase():"OFF";
            tip="Dynamic Range Optimizer: Recovers shadow detail in high-contrast scenes";
        } else if (mode == 10) {
            if(mc.isConfirmingDelete()){activeCells=2;labels=new String[]{"ARE YOU SURE?","CANCEL"};values[0]="[ CONFIRM DELETE ]";values[1]="[ GO BACK ]";if(selection==0) tip="WARNING: This will permanently delete the recipe from the SD card."; else tip="Cancel and return to the Recipe Manager.";}
            else{
                activeCells=4;labels=new String[]{"SAVE","BROWSE","RESET","DELETE"};
                vaultItems=host.getRecipeManager().getVaultItems(); if(vaultIndex>=vaultItems.size()||vaultIndex<0) vaultIndex=0;
                String vn=(vaultItems.isEmpty()||vaultItems.get(0).filename.equals("NONE"))?"EMPTY":vaultItems.get(vaultIndex).profileName;
                if(vn.length()>10) vn=vn.substring(0,8)+"..";
                values[0]="[ RENAME ]"; values[1]="< "+vn+" >"; values[2]="[ DEFAULT ]";
                values[3]=(!vaultItems.isEmpty()&&!vaultItems.get(0).filename.equals("NONE"))?"[ TRASH ]":"---";
                if(selection==0) tip="Press ENTER to RENAME and SAVE this Slot to the Vault.";
                else if(selection==1) tip="Scroll wheel to browse. WARNING: LIVE VIEW will overwrite Slot.";
                else if(selection==2) tip="Press ENTER to wipe this Slot back to default settings.";
                else if(selection==3) tip="Permanently delete the currently selected Vault recipe.";
            }
            if(tvTop!=null){
                if(mc.isNamingMode()){StringBuilder sb=new StringBuilder("NAME: ");char[] buf=mc.getNameBuffer();int pos=mc.getNameCursorPos();for(int i=0;i<buf.length;i++){if(i==pos)sb.append("[").append(buf[i]).append("]");else sb.append(buf[i]);}tvTop.setText(sb.toString());tvTop.setTextColor(Color.YELLOW);}
                else{tvTop.setText("RECIPE MANAGER - SLOT "+(host.getRecipeManager().getCurrentSlot()+1));tvTop.setTextColor(Color.WHITE);}
                tvTop.setVisibility(View.VISIBLE);
            }
        }

        // Render cells
        for(int i=0;i<9;i++){
            if(i<activeCells){cells[i].setVisibility(View.VISIBLE);cellLabels[i].setText(labels[i]);cellValues[i].setText(values[i]);
                if(i==selection){cellLabels[i].setTextColor(Color.rgb(227,69,20));cellValues[i].setTextColor(Color.rgb(227,69,20));}
                else{cellLabels[i].setTextColor(Color.GRAY);cellValues[i].setTextColor(Color.WHITE);}
            } else cells[i].setVisibility(View.GONE);
        }
        if(tooltip!=null){tooltip.setText(tip);tooltip.setVisibility(tip.isEmpty()?View.GONE:View.VISIBLE);}
    }
}
