package com.hongcha.teslawatch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private String BASE;
    private String KEY;

    // 색상
    private static final int BG      = Color.parseColor("#151515");
    private static final int C_LABEL = Color.parseColor("#dcdcdc");
    private static final int C_DASH  = Color.parseColor("#7d7f7e");
    private static final int C_OUT   = Color.parseColor("#e8e8e8");
    private static final int C_FILL  = Color.parseColor("#b8bab9");
    private static final int C_LOW   = Color.parseColor("#e82127");
    private static final int C_PCT   = Color.parseColor("#f0f0f0");
    // 아이콘 상태색
    private static final int C_OFF   = Color.parseColor("#8a8c8b"); // 꺼짐
    private static final int C_YEL   = Color.parseColor("#f5c518"); // 잠금해제
    private static final int C_WHT   = Color.parseColor("#ffffff"); // 프렁크/트렁크 열림
    private static final int C_GRN   = Color.parseColor("#2ecc71"); // 충전구 열림
    private static final int C_HEAT  = Color.parseColor("#e8501c"); // 히터 강/휠 ON
    private static final int C_S1    = Color.parseColor("#f0b060"); // 히터 1단
    private static final int C_S2    = Color.parseColor("#f08030"); // 히터 2단
    private static final int C_ARROW = Color.parseColor("#cfd1d0"); // 온도 화살표

    private float S = 1f;                 // 480 디자인 → 실제 화면 스케일
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final double MI = 1.60934;             // mile → km

    private FrameLayout root;
    private FrameLayout[] screen = new FrameLayout[4];    // 0=메인 1=제어 2=공조 3=상태
    private FrameLayout mainOverlay;                      // 메인 오버레이(점선/라벨/배터리) 페이드용
    private ImageView mainCarBg;                          // s1.png 차량이미지 — 첫 애니 단계
    private FrameLayout controlOverlay, hvacOverlay;      // 제어/공조 아이콘·글자 페이드용
    private boolean introPending = false;                 // 창이 보인 뒤 인트로 재생
    private int cur = 0;

    // 화면4(상태)
    private ImageView statBg;
    private TextView carName, statText;
    private ScrollView statScroll;
    private JSONObject lastResp;

    // 화면1
    private BatteryView battery;
    private TextView battText;
    private int lastSoc = -1;
    private DashLine dashCtl, dashStat, dashHvac;
    private TextView lblCtl, lblStat, lblHvac;

    // 화면2(제어) 아이콘 + 상태 (null=미확인)
    private ImageView icDoor, icFrunk, icTrunk, icCharge;
    private Boolean stLocked, stFrunkOpen, stTrunkOpen, stChargeOpen;

    // 화면3(공조)
    private ImageView icWheel, icArrowUp, icArrowDown;
    private ImageView[] seatIcon = new ImageView[5];
    private final int[] SEAT_ID = {0, 1, 2, 4, 5};          // Tesla 히터 인덱스 (FL,FR,RL,RC,RR)
    private final String[] SEAT_NM = {"운전석", "조수석", "뒤좌", "뒤중", "뒤우"};
    private final int[] seatLevel = {0, 0, 0, 0, 0};
    private boolean stWheel = false;
    private TextView tempText, hintText;
    private int tempSet = -1;                                // 표시/편집 중 목표온도(℃)
    private ImageView hvacOn;                                // 공조 ON 배경(페이드용 오버레이)
    private boolean stClimate = false;                       // 공조 ON 여부

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        BASE = getString(R.string.bridge_base);
        KEY  = getString(R.string.bridge_key);
        S = getResources().getDisplayMetrics().widthPixels / 480f;

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < 4; i++) {
            screen[i] = new FrameLayout(this);
            screen[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            root.addView(screen[i], new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            if (i == 1) addBg(screen[i], "s2.png");   // 메인(0)/공조(2)/상태(3)는 각 build에서 처리
        }

        buildMain();
        buildControl();
        buildHvac();
        buildStatus();

        // Wear OS 4: 뒤로가기를 시스템이 가로채므로 콜백으로 직접 처리
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new OnBackInvokedCallback() {
                        @Override public void onBackInvoked() {
                            if (cur != 0) show(0);   // 서브화면 → 메인
                            else finish();           // 메인 → 종료
                        }
                    });
        }

        show(0);   // 초기 진입도 오버레이 페이드 인
        fetchState();
        ui.postDelayed(refreshLoop, 60000);
    }

    // ── 화면 전환 ──
    private void show(int idx) {
        cur = idx;
        for (int i = 0; i < 4; i++) {
            if (i == idx) {
                if (i == 0) {
                    // 메인: bg는 즉시, 점선이 자라고 → 글자 페이드 인
                    screen[0].setAlpha(1f);
                    screen[0].setVisibility(View.VISIBLE);
                    startMainIntro();
                } else {
                    // 제어/공조/상태: 배경 먼저 페이드 → 아이콘·글자 뒤이어 페이드
                    screen[i].setAlpha(0f);
                    screen[i].setVisibility(View.VISIBLE);
                    screen[i].animate().alpha(1f).setDuration(250).start();
                    FrameLayout ov = (i == 1) ? controlOverlay : (i == 2) ? hvacOverlay : null;
                    if (ov != null) {
                        ov.setAlpha(0f);
                        ov.animate().alpha(1f).setStartDelay(180).setDuration(400).start();
                    }
                }
            } else {
                screen[i].setVisibility(View.GONE);
            }
        }
        if (idx == 0) renderMain();
        if (idx == 1) renderControl();
        if (idx == 2) { renderHvac(); fetchState(); }   // 진입 시 공조상태 동기화(비깨움 GET)
        if (idx == 3) {                                  // 상태: 스크롤/페이드 초기화 후 동기화
            if (statScroll != null) statScroll.scrollTo(0, 0);
            if (statBg != null) statBg.setAlpha(1f);
            if (carName != null) carName.setAlpha(1f);
            renderStatus();
            fetchState();
        }
    }

    // 뒤로가기/스와이프-dismiss는 onBackPressed 하나로만 처리(이중처리 방지)
    @Override
    public void onBackPressed() {
        if (cur != 0) { show(0); return; }   // 서브화면 → 메인
        super.onBackPressed();               // 메인에서만 종료
    }

    // ═══════════ 화면1: 메인 ═══════════
    private void buildMain() {
        FrameLayout s = screen[0];
        // 맨 아래: bg.png (항상 즉시 표시되는 어두운 배경)
        addBg(s, "bg.png");
        // 그 위: s1.png 차량 이미지 (애니 첫 단계에서 페이드 인)
        mainCarBg = new ImageView(this);
        mainCarBg.setScaleType(ImageView.ScaleType.FIT_XY);
        mainCarBg.setAlpha(0f);
        try {
            InputStream is = getAssets().open("s1.png");
            mainCarBg.setImageBitmap(BitmapFactory.decodeStream(is));
            is.close();
        } catch (Exception e) {}
        s.addView(mainCarBg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // 오버레이 컨테이너(bg 위) — 진입 시 이것만 페이드 인
        mainOverlay = new FrameLayout(this);
        s.addView(mainOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 배터리 아이콘 (상단 중앙)
        battery = new BatteryView(this);
        mainOverlay.addView(battery, lp(217, 14, 46, 23));

        // 배터리 %
        battText = new TextView(this);
        battText.setText("–%");
        battText.setTextColor(C_PCT);
        battText.setGravity(Gravity.CENTER);
        battText.setTypeface(battText.getTypeface(), android.graphics.Typeface.BOLD);
        battText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 23 * S);
        mainOverlay.addView(battText, lp(180, 40, 120, 32));

        // 점선들 (차량 → 글자). 제어/상태는 아래→위, 공조는 위→아래로 자람
        dashCtl  = new DashLine(this, false); mainOverlay.addView(dashCtl,  lp(78, 134, 4, 120));
        dashStat = new DashLine(this, false); mainOverlay.addView(dashStat, lp(374, 108, 4, 78));
        dashHvac = new DashLine(this, true);  mainOverlay.addView(dashHvac, lp(280, 300, 4, 98));

        // 라벨 — 클릭영역 168×72 (가로 -5%, 세로 +20%), 리플은 글자 크기 알약형
        lblCtl  = label("제어", new Runnable(){ public void run(){ show(1); } });
        lblStat = label("상태", new Runnable(){ public void run(){ show(3); } });
        lblHvac = label("공조", new Runnable(){ public void run(){ show(2); } });
        mainOverlay.addView(lblCtl,  lp(-4, 71, 168, 72));
        mainOverlay.addView(lblStat, lp(292, 44, 168, 72));
        mainOverlay.addView(lblHvac, lp(198, 396, 168, 72));

        renderMain();
    }

    private TextView label(String text, final Runnable onTap) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_LABEL);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32 * S);
        // 리플은 클릭영역이 아니라 글자에 맞춘 알약 모양
        t.setBackground(pillRipple(Math.round(36 * S), Math.round(13 * S)));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (onTap != null) onTap.run(); }
        });
        return t;
    }

    private final Runnable introRun = new Runnable() { @Override public void run() { playMainIntro(); } };

    /** 메인 진입 연출: 요소를 즉시 숨기고, 창이 실제로 보일 때 애니메이션 시작 */
    private void startMainIntro() {
        if (mainOverlay == null || dashCtl == null) return;
        mainOverlay.setAlpha(1f);
        if (mainCarBg != null) mainCarBg.setAlpha(0f);
        dashCtl.setProgress(0f); dashStat.setProgress(0f); dashHvac.setProgress(0f);
        lblCtl.setAlpha(0f); lblStat.setAlpha(0f); lblHvac.setAlpha(0f);
        battery.setAlpha(0f); battText.setAlpha(0f);
        if (hasWindowFocus()) mainOverlay.post(introRun);   // 이미 보이는 중(뒤로가기 복귀)
        else introPending = true;                          // 첫 실행: 창 뜬 뒤에 재생
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && introPending) {
            introPending = false;
            if (mainOverlay != null) mainOverlay.post(introRun);
        }
    }

    private void playMainIntro() {
        // 1단계: s1.png(차량이미지) 페이드 인 300ms
        if (mainCarBg != null) {
            mainCarBg.animate().alpha(1f).setDuration(300).withEndAction(new Runnable() {
                @Override public void run() { playMainIntroDash(); }
            }).start();
        } else {
            playMainIntroDash();
        }
    }

    private void playMainIntroDash() {
        // 2단계: 점선 성장 650ms → 3단계: 라벨/배터리 페이드 인 400ms
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(650);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float f = ((Float) a.getAnimatedValue()).floatValue();
                dashCtl.setProgress(f); dashStat.setProgress(f); dashHvac.setProgress(f);
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                lblCtl.animate().alpha(1f).setDuration(400).start();
                lblStat.animate().alpha(1f).setDuration(400).start();
                lblHvac.animate().alpha(1f).setDuration(400).start();
                battery.animate().alpha(1f).setDuration(400).start();
                battText.animate().alpha(1f).setDuration(400).start();
            }
        });
        va.start();
    }

    private void renderMain() {
        if (battText == null) return;
        battText.setText((lastSoc >= 0 ? lastSoc : "–") + "%");
        if (battery != null) battery.setLevel(lastSoc);
    }

    // ═══════════ 화면2: 제어 ═══════════
    private void buildControl() {
        FrameLayout s = screen[1];

        // 아이콘들은 오버레이에 담아 진입 시 페이드
        controlOverlay = new FrameLayout(this);
        s.addView(controlOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 아이콘 크기는 유지, 클릭영역만 15% 크게 (addIcon) + 누를 때 흰 원형 리플
        icFrunk = iconConfirm(R.drawable.ic_frunk, "프렁크를", "프렁크", "/api/trunk?which=front");
        addIcon(controlOverlay, icFrunk, 240, 62, 56);    // 맨 위 중앙
        icDoor = iconBtn(R.drawable.ic_door, new Runnable(){ public void run(){ toggleLock(); } });
        addIcon(controlOverlay, icDoor, 240, 240, 64);    // 정중앙
        icCharge = iconBtn(R.drawable.ic_chargeport, new Runnable(){ public void run(){ toggleCharge(); } });
        addIcon(controlOverlay, icCharge, 92, 320, 56);   // 좌측 2/3
        icTrunk = iconConfirm(R.drawable.ic_trunk, "트렁크를", "트렁크", "/api/trunk?which=rear");
        addIcon(controlOverlay, icTrunk, 240, 418, 56);   // 맨 아래 중앙

        renderControl();
    }

    private ImageView iconBtn(int res, final Runnable onTap) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(C_OFF);
        iv.setBackground(circleRipple());
        iv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onTap.run(); }
        });
        return iv;
    }

    /** 글자 크기에 맞춘 알약(pill) 리플 — 클릭영역보다 작게 안쪽으로 여백 */
    private Drawable pillRipple(int insetH, int insetV) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(999f);
        mask.setColor(Color.WHITE);
        RippleDrawable rd = new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#40ffffff")), null, mask);
        return new InsetDrawable(rd, insetH, insetV, insetH, insetV);
    }

    /** 누를 때 나타나는 반투명 흰색 원형 리플 (클릭영역 크기 표시) */
    private RippleDrawable circleRipple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#40ffffff")), null, mask);
    }

    /** 아이콘 시각크기는 유지하고 클릭영역만 15% 확대 (중심 cx,cy 기준) */
    private void addIcon(FrameLayout s, ImageView iv, int cx, int cy, int iconSize) {
        int view = Math.round(iconSize * 1.15f);
        int pad = Math.round((view - iconSize) / 2f * S);
        iv.setPadding(pad, pad, pad, pad);
        s.addView(iv, lp(cx - view / 2, cy - view / 2, view, view));
    }

    /** 롱프레스로만 작동 + 확인창 (프렁크/트렁크 오작동 방지) */
    private ImageView iconConfirm(int res, final String what, final String label, final String path) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(C_OFF);
        iv.setBackground(circleRipple());
        iv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toast("길게 눌러 여세요"); }
        });
        iv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(what + " 여시겠습니까?")
                        .setPositiveButton("확인", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { cmd(path, label); }
                        })
                        .setNegativeButton("취소", null)
                        .show();
                return true;
            }
        });
        return iv;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void renderControl() {
        if (icDoor == null) return;
        // 잠김/미확인 → 회색, 잠금해제 → 노랑
        icDoor.setColorFilter((stLocked != null && !stLocked) ? C_YEL : C_OFF);
        icFrunk.setColorFilter(tb(stFrunkOpen) ? C_WHT : C_OFF);
        icTrunk.setColorFilter(tb(stTrunkOpen) ? C_WHT : C_OFF);
        icCharge.setColorFilter(tb(stChargeOpen) ? C_GRN : C_OFF);
    }

    private void toggleLock() {
        boolean locked = tb(stLocked);
        cmd("/api/command/" + (locked ? "unlock" : "lock"), locked ? "잠금 해제" : "잠금");
    }

    private void toggleCharge() {
        boolean open = tb(stChargeOpen);
        cmd("/api/command/" + (open ? "charge_port_close" : "charge_port_open"), open ? "충전구 닫기" : "충전구 열기");
    }

    private static boolean tb(Boolean b) { return b != null && b.booleanValue(); }

    // ═══════════ 화면3: 공조 ═══════════
    private void buildHvac() {
        FrameLayout s = screen[2];

        // 배경 2겹: off(기본) + on(오버레이, 처음엔 투명 → 공조 켜지면 페이드 인)
        addBg(s, "s3off.png");
        hvacOn = new ImageView(this);
        hvacOn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap onBm = loadAsset("s3on.png");
        if (onBm != null) hvacOn.setImageBitmap(onBm);
        hvacOn.setAlpha(0f);
        s.addView(hvacOn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 아이콘/글자는 오버레이에 담아 진입 시 페이드
        hvacOverlay = new FrameLayout(this);
        s.addView(hvacOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 스티어링 휠 히터 (운전석 바로 위)
        icWheel = seatBtn(R.drawable.ic_wheel, -1);
        hvacOverlay.addView(icWheel, lp(156, 68, 44, 44));

        // 앞좌석 (운전석/조수석)
        seatIcon[0] = seatBtn(R.drawable.ic_seat, 0); hvacOverlay.addView(seatIcon[0], lp(173, 143, 44, 44));
        seatIcon[1] = seatBtn(R.drawable.ic_seat, 1); hvacOverlay.addView(seatIcon[1], lp(268, 143, 44, 44));
        // 뒷좌석 3개
        seatIcon[2] = seatBtn(R.drawable.ic_seat, 2); hvacOverlay.addView(seatIcon[2], lp(158, 268, 44, 44));
        seatIcon[3] = seatBtn(R.drawable.ic_seat, 3); hvacOverlay.addView(seatIcon[3], lp(215, 281, 44, 44));
        seatIcon[4] = seatBtn(R.drawable.ic_seat, 4); hvacOverlay.addView(seatIcon[4], lp(271, 266, 44, 44));

        // 온도 표시 (탭 → 깨움 + 설정온도로 공조 ON)
        tempText = new TextView(this);
        tempText.setTextColor(C_PCT);
        tempText.setGravity(Gravity.CENTER);
        tempText.setTypeface(tempText.getTypeface(), android.graphics.Typeface.BOLD);
        tempText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * S);
        tempText.setText("–℃");
        tempText.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ applyClimate(); } });
        tempText.setOnLongClickListener(new View.OnLongClickListener(){ @Override public boolean onLongClick(View v){ climateOff(); return true; } });
        hvacOverlay.addView(tempText, lp(180, 393, 120, 44));

        // 힌트: 공조 ON일 때만 "길게 눌러서 공조 끄기"
        hintText = new TextView(this);
        hintText.setText("길게 눌러서 공조 끄기");
        hintText.setTextColor(C_ARROW);
        hintText.setGravity(Gravity.CENTER);
        hintText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12 * S);
        hintText.setVisibility(View.GONE);
        hvacOverlay.addView(hintText, lp(120, 440, 240, 20));

        // 온도 내림(좌, 180° 뒤집힘) / 올림(우, 정방향)
        icArrowDown = arrowBtn(true);
        hvacOverlay.addView(icArrowDown, lp(130, 395, 40, 40));
        icArrowUp = arrowBtn(false);
        hvacOverlay.addView(icArrowUp, lp(310, 395, 40, 40));

        renderHvac();
    }

    private ImageView arrowBtn(final boolean down) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_arrow);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(C_ARROW);
        if (down) iv.setRotation(180);
        iv.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){ adjustTemp(down ? -1 : 1); } });
        return iv;
    }

    /** 시트/휠 히터 아이콘 (idx 0~4 시트, -1 휠) */
    private ImageView seatBtn(int res, final int idx) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(C_OFF);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (idx < 0) toggleWheel(); else cycleSeat(idx); }
        });
        return iv;
    }

    private void cycleSeat(int idx) {
        int next = (seatLevel[idx] + 1) % 4;   // 0→1→2→3→0
        seatLevel[idx] = next;
        paintSeat(idx);
        cmd("/api/seat?seat=" + SEAT_ID[idx] + "&level=" + next,
                SEAT_NM[idx] + " " + (next == 0 ? "OFF" : next + "단"));
    }

    private void toggleWheel() {
        stWheel = !stWheel;
        paintWheel();
        cmd("/api/steering?on=" + stWheel, "스티어링 히터 " + (stWheel ? "ON" : "OFF"));
    }

    private void adjustTemp(int d) {
        if (tempSet < 0) tempSet = 21;
        tempSet = Math.max(15, Math.min(28, tempSet + d));
        if (tempText != null) tempText.setText(tempSet + "℃");
    }

    private void applyClimate() {
        if (tempSet < 0) return;
        final int t = tempSet;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!ensureAwake("공조")) return;
                    ui.post(new Runnable(){ public void run(){ toast(t + "℃ 공조 켜는 중…"); } });
                    try { httpPost(BASE + "/api/set_temp?celsius=" + t + "&key=" + KEY); } catch (Exception e) {}
                    boolean ok = false;
                    try { ok = httpPost(BASE + "/api/command/climate_on?key=" + KEY); } catch (Exception e) {}
                    final boolean fok = ok;
                    ui.post(new Runnable(){ public void run(){ toast(fok ? (t + "℃ 공조 ON") : "공조 실패"); if (fok) { stClimate = true; renderHvac(); } } });
                } finally {
                    ui.postDelayed(new Runnable(){ public void run(){ fetchState(); } }, 2500);
                }
            }
        }).start();
    }

    private void climateOff() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!ensureAwake("공조 끄기")) return;
                    ui.post(new Runnable(){ public void run(){ toast("공조 끄는 중…"); } });
                    boolean ok = false;
                    try { ok = httpPost(BASE + "/api/command/climate_off?key=" + KEY); } catch (Exception e) {}
                    final boolean fok = ok;
                    ui.post(new Runnable(){ public void run(){ toast(fok ? "공조 OFF" : "공조 끄기 실패"); if (fok) { stClimate = false; renderHvac(); } } });
                } finally {
                    ui.postDelayed(new Runnable(){ public void run(){ fetchState(); } }, 2500);
                }
            }
        }).start();
    }

    private void renderHvac() {
        if (tempText == null) return;
        for (int i = 0; i < 5; i++) paintSeat(i);
        paintWheel();
        tempText.setText((tempSet >= 0 ? tempSet : "–") + "℃");
        if (hvacOn != null) hvacOn.animate().alpha(stClimate ? 1f : 0f).setDuration(400).start();
        if (hintText != null) hintText.setVisibility(stClimate ? View.VISIBLE : View.GONE);
    }

    private void paintSeat(int idx) {
        if (seatIcon[idx] == null) return;
        int l = seatLevel[idx];
        int c = l <= 0 ? C_OFF : (l == 1 ? C_S1 : (l == 2 ? C_S2 : C_HEAT));
        seatIcon[idx].setColorFilter(c);
    }

    private void paintWheel() {
        if (icWheel != null) icWheel.setColorFilter(stWheel ? C_HEAT : C_OFF);
    }

    // ═══════════ 화면4: 상태 (스크롤) ═══════════
    private void buildStatus() {
        final FrameLayout s = screen[3];
        s.setBackgroundColor(Color.parseColor("#161719"));

        // 차 이미지 배경 (스크롤 시 페이드아웃)
        statBg = new ImageView(this);
        statBg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bm = loadAsset("s4.png");
        if (bm != null) statBg.setImageBitmap(bm);
        s.addView(statBg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 스크롤 텍스트
        statScroll = new ScrollView(this);
        statScroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        View spacer = new View(this);   // 차 이미지 자리만큼 상단 여백
        col.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(275 * S)));
        statText = new TextView(this);
        statText.setTextColor(Color.parseColor("#e8e8e8"));
        statText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19 * S);
        statText.setLineSpacing(Math.round(9 * S), 1f);
        int px = Math.round(42 * S);
        statText.setPadding(px, 0, px, Math.round(130 * S));   // 원형 잘림 방지: 하단 3줄분 여백
        col.addView(statText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        statScroll.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        s.addView(statScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 차 이름 (맨 위, 큰 글씨) — 스크롤 시 페이드아웃
        carName = new TextView(this);
        carName.setText("홍차");
        carName.setTextColor(Color.WHITE);
        carName.setGravity(Gravity.CENTER);
        carName.setTypeface(carName.getTypeface(), android.graphics.Typeface.BOLD);
        carName.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40 * S);
        s.addView(carName, lp(90, 12, 300, 56));

        // 스크롤에 따라 bg + 이름 페이드
        final float fadeEnd = 120f * S;
        statScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                float a = Math.max(0f, 1f - y / fadeEnd);
                statBg.setAlpha(a);
                carName.setAlpha(a);
            }
        });

        renderStatus();
    }

    private void renderStatus() {
        if (statText == null) return;
        JSONObject r = lastResp;
        if (r == null) { statText.setText("정보 불러오는 중…"); return; }
        JSONObject cs = r.optJSONObject("charge_state");
        JSONObject cl = r.optJSONObject("climate_state");
        JSONObject vs = r.optJSONObject("vehicle_state");
        StringBuilder b = new StringBuilder();
        if (cs != null) {
            b.append("배터리 : ").append(cs.optInt("battery_level", -1)).append(" %\n");
            b.append("남은 주행거리 : ").append(Math.round(cs.optDouble("battery_range", 0) * MI)).append(" km\n");
        }
        if (vs != null && vs.has("odometer"))
            b.append("총 주행거리 : ").append(String.format("%,d", Math.round(vs.optDouble("odometer", 0) * MI))).append(" km\n");
        if (cl != null) {
            if (cl.has("inside_temp"))  b.append("실내 온도 : ").append(fmt1(cl.optDouble("inside_temp"))).append(" ℃\n");
            if (cl.has("outside_temp")) b.append("외기 온도 : ").append(fmt1(cl.optDouble("outside_temp"))).append(" ℃\n");
            if (cl.has("driver_temp_setting")) b.append("설정 온도 : ").append(Math.round(cl.optDouble("driver_temp_setting"))).append(" ℃\n");
            b.append("공조 : ").append(cl.optBoolean("is_climate_on") ? "ON" : "OFF").append("\n");
        }
        if (vs != null) b.append("잠금 : ").append(vs.optBoolean("locked") ? "잠김" : "해제").append("\n");
        if (cs != null) {
            b.append("충전 : ").append(chargeKo(cs.optString("charging_state", ""))).append("\n");
            if (cs.has("charge_limit_soc")) b.append("충전 한도 : ").append(cs.optInt("charge_limit_soc")).append(" %\n");
        }
        if (vs != null && vs.has("tpms_pressure_fl")) {
            b.append("타이어 앞 : ").append(psi(vs.optDouble("tpms_pressure_fl"))).append(" / ").append(psi(vs.optDouble("tpms_pressure_fr"))).append(" psi\n");
            b.append("타이어 뒤 : ").append(psi(vs.optDouble("tpms_pressure_rl"))).append(" / ").append(psi(vs.optDouble("tpms_pressure_rr"))).append(" psi\n");
        }
        if (vs != null && vs.has("sentry_mode")) b.append("감시 모드 : ").append(vs.optBoolean("sentry_mode") ? "ON" : "OFF").append("\n");
        b.append("\n차대번호\n").append(r.optString("vin", "-"));
        statText.setText(b.toString());

        String nm = vs != null ? vs.optString("vehicle_name", "") : "";
        carName.setText(nm.isEmpty() ? "홍차" : nm);
    }

    private static String fmt1(double d) { return String.format("%.1f", d); }
    private static String psi(double bar) { return String.valueOf(Math.round(bar * 14.5038)); }
    private static String chargeKo(String s) {
        if (s == null) return "-";
        switch (s) {
            case "Charging": return "충전중";
            case "Complete": return "완료";
            case "Stopped": return "중지";
            case "Disconnected": return "미연결";
            case "NoPower": return "대기";
            default: return s.isEmpty() ? "-" : s;
        }
    }

    // ── 레이아웃 헬퍼 (480 디자인 좌표 → 스케일) ──
    private FrameLayout.LayoutParams lp(int x, int y, int w, int h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                Math.round(w * S), Math.round(h * S));
        p.leftMargin = Math.round(x * S);
        p.topMargin  = Math.round(y * S);
        return p;
    }

    private void addBg(FrameLayout s, String asset) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bm = loadAsset(asset);
        if (bm != null) iv.setImageBitmap(bm);
        s.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private Bitmap loadAsset(String name) {
        InputStream is = null;
        try {
            is = getAssets().open(name);
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignore) {}
        }
    }

    // ── 네트워크 ──
    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() { fetchState(); ui.postDelayed(this, 60000); }
    };

    private void fetchState() {
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject resp = null;
                try {
                    String body = httpGet(BASE + "/api/state?key=" + KEY);
                    JSONObject j = new JSONObject(body);
                    resp = j.optJSONObject("response");
                } catch (Exception e) {
                    // 무시
                }
                final JSONObject fresp = resp;
                ui.post(new Runnable() {
                    @Override public void run() { applyState(fresp); }
                });
            }
        }).start();
    }

    private void applyState(JSONObject resp) {
        if (resp == null) return;   // 잠든 차 등 — 이전 값 유지
        lastResp = resp;
        JSONObject cs = resp.optJSONObject("charge_state");
        JSONObject vs = resp.optJSONObject("vehicle_state");
        JSONObject cl = resp.optJSONObject("climate_state");
        if (cs != null) {
            if (cs.has("battery_level")) lastSoc = cs.optInt("battery_level", lastSoc);
            if (cs.has("charge_port_door_open")) stChargeOpen = cs.optBoolean("charge_port_door_open");
        }
        if (vs != null) {
            if (vs.has("locked")) stLocked = vs.optBoolean("locked");
            if (vs.has("ft")) stFrunkOpen = vs.optInt("ft", 0) != 0;
            if (vs.has("rt")) stTrunkOpen = vs.optInt("rt", 0) != 0;
        }
        if (cl != null) {
            seatLevel[0] = cl.optInt("seat_heater_left", seatLevel[0]);
            seatLevel[1] = cl.optInt("seat_heater_right", seatLevel[1]);
            seatLevel[2] = cl.optInt("seat_heater_rear_left", seatLevel[2]);
            seatLevel[3] = cl.optInt("seat_heater_rear_center", seatLevel[3]);
            seatLevel[4] = cl.optInt("seat_heater_rear_right", seatLevel[4]);
            if (cl.has("steering_wheel_heater")) stWheel = cl.optBoolean("steering_wheel_heater");
            if (cl.has("is_climate_on")) stClimate = cl.optBoolean("is_climate_on");
            if (cl.has("driver_temp_setting") && (tempSet < 0 || cur != 2)) {   // 공조화면 편집 중엔 유지
                tempSet = (int) Math.round(cl.optDouble("driver_temp_setting", 21));
            }
        }
        renderMain();
        renderControl();
        renderHvac();
        renderStatus();
    }

    private void cmd(final String path, final String label) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!ensureAwake(label)) return;
                    ui.post(new Runnable(){ public void run(){ toast(label + " 전송…"); } });
                    boolean ok = false;
                    try { ok = httpPost(BASE + path + (path.contains("?") ? "&" : "?") + "key=" + KEY); }
                    catch (Exception e) {}
                    final boolean fok = ok;
                    ui.post(new Runnable(){ public void run(){ toast(label + (fok ? " 완료" : " 실패")); } });
                } finally {
                    ui.postDelayed(new Runnable(){ public void run(){ fetchState(); } }, 2000);
                    ui.postDelayed(new Runnable(){ public void run(){ fetchState(); } }, 5000);
                }
            }
        }).start();
    }

    /** 백그라운드 스레드에서 호출: 자고 있으면 깨우고 최대 10초 대기. 실패 시 토스트+false. */
    private boolean ensureAwake(final String failLabel) {
        if (isAwake()) return true;
        ui.post(new Runnable(){ public void run(){ toast("차량 깨우는 중…"); } });
        try { httpPost(BASE + "/api/command/wake?key=" + KEY); } catch (Exception e) {}
        long deadline = System.currentTimeMillis() + 30000;   // Tesla 웨이크는 느릴 수 있음
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            if (isAwake()) return true;
        }
        ui.post(new Runnable(){ public void run(){ toast(failLabel + " 실패 (깨우기 시간초과)"); } });
        return false;
    }

    /** 실시간 응답(cached=false)이 오면 깨어있음. 캐시 응답은 자는 중으로 간주 */
    private boolean isAwake() {
        try {
            String b = httpGet(BASE + "/api/state?key=" + KEY);
            JSONObject j = new JSONObject(b);
            return j.optJSONObject("response") != null && !j.optBoolean("cached", false);
        } catch (Exception e) {
            return false;
        }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod("GET");
        try {
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private boolean httpPost(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        try {
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            byte[] buf = new byte[2048];
            while (is != null && is.read(buf) != -1) { /* drain */ }
            return code < 400;
        } finally {
            c.disconnect();
        }
    }

    // ════════ 커스텀 뷰 ════════

    /** 가로 배터리 아이콘 (48×24 뷰박스 기준, 레벨 비례 채움) */
    private class BatteryView extends View {
        private int level = -1;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);

        BatteryView(Context ctx) {
            super(ctx);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(C_OUT);
            fill.setStyle(Paint.Style.FILL);
        }

        void setLevel(int l) { level = l; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float sx = getWidth() / 48f, sy = getHeight() / 24f;
            stroke.setStrokeWidth(2 * sx);
            cv.drawRoundRect(1 * sx, 4 * sy, 41 * sx, 20 * sy, 3 * sx, 3 * sx, stroke);
            fill.setColor(C_OUT);
            cv.drawRoundRect(43 * sx, 9 * sy, 46.5f * sx, 15 * sy, 1 * sx, 1 * sx, fill);
            if (level >= 0) {
                fill.setColor(level <= 15 ? C_LOW : C_FILL);
                float w = 34f * Math.max(0, Math.min(100, level)) / 100f;
                if (w > 0) cv.drawRoundRect(4 * sx, 7 * sy, (4 + w) * sx, 17 * sy, 1.5f * sx, 1.5f * sx, fill);
            }
        }
    }

    /** 수직 점선 (progress로 자라나는 애니메이션) */
    private class DashLine extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean downward;   // true: 위→아래, false: 아래→위
        private float prog = 1f;
        DashLine(Context ctx, boolean downward) {
            super(ctx);
            this.downward = downward;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(C_DASH);
            p.setStrokeWidth(2 * S);
            p.setPathEffect(new DashPathEffect(new float[]{6 * S, 6 * S}, 0));
        }
        void setProgress(float f) { prog = f; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            if (prog <= 0f) return;
            float x = getWidth() / 2f, h = getHeight();
            if (downward) cv.drawLine(x, 0, x, h * prog, p);
            else          cv.drawLine(x, h, x, h - h * prog, p);
        }
    }
}
