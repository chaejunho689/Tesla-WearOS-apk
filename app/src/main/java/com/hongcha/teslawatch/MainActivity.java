package com.hongcha.teslawatch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.MotionEvent;
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
    private static final int BG      = Color.parseColor("#161719");
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
    private static final int C_BUSY  = Color.parseColor("#45484a"); // 명령 전송중(어두운 회색)

    private volatile boolean cmdBusy = false;                       // 명령 전송중 재클릭 차단
    private boolean swUpdateAvail = false;                          // 신규 소프트웨어 있음
    private boolean swBannerShown = false;                          // 이번 메인 진입에 배너 표시함
    private boolean lastAwake = false;                              // 차량 깨어있음(sleep/wifi 아이콘)
    private ImageView statusIcon;                                   // 메인 하단 sleep/wifi

    private float S = 1f;                 // 480 디자인 → 실제 화면 스케일
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final double MI = 1.60934;             // mile → km

    private FrameLayout root;
    private FrameLayout[] screen = new FrameLayout[5];    // 0=메인 1=제어 2=공조 3=상태 4=충전
    private FrameLayout mainOverlay;                      // 메인 오버레이(점선/라벨/배터리) 페이드용
    private ImageView mainCarBg;                          // s1.png 차량이미지 — 첫 애니 단계
    private FrameLayout controlOverlay, hvacOverlay;      // 제어/공조 아이콘·글자 페이드용
    private boolean introPending = false;                 // 창이 보인 뒤 인트로 재생
    private int cur = 0;

    // 화면5(충전)
    private ImageView chgCarBg;                           // s5.png 차량이미지 — 첫 애니 단계
    private FrameLayout chgOverlay;                       // 충전 정보(게이지 등) 페이드용
    private DashLine dashOpen;                            // 충전구 → 번개아이콘 점선
    private ImageView icOpen;                             // 번개(충전 잠금해제)
    private TextView chgSoc, chgRange, chgLimitTxt, chgEta;
    private ChargeBar chargeBar;                          // 게이지 + 드래그 노란원
    private int chgLimitSel = 80;                         // 선택 충전 제한(50~100)
    private boolean chgDragging = false;                  // 노란원 드래그 중(차량값 덮어쓰기 방지)

    // 화면4(상태)
    private ImageView statBg;
    private TextView carName, statText;
    private ScrollView statScroll;
    private JSONObject lastResp;

    // 화면1
    private BatteryView battery;
    private TextView battText;
    private int lastSoc = -1;
    private int lastRangeKm = -1;            // 남은 주행거리 (km)
    private boolean showRange = false;       // false=배터리% / true=주행거리km
    private SharedPreferences prefs;
    private DashLine dashCtl, dashStat, dashHvac, dashChg;
    private TextView lblCtl, lblStat, lblHvac, lblChg;

    // 충전 상태
    private int lastLimit = 80;              // 차량의 현재 충전 제한
    private int lastMinToFull = -1;          // 완충까지 분
    private boolean stCharging = false;
    private boolean stChargeComplete = false;
    private String chargingState = "";       // "Charging"/"Complete"/"Stopped"/"NoPower"/"Disconnected"/"Starting"
    private long chgAttemptTs = 0;           // 마지막 한도 변경 시각(실패 판정용)

    /** 충전기 연결됨: Disconnected 외 모든 상태 */
    private boolean chargerConnected() {
        return chargingState != null && !chargingState.isEmpty() && !"Disconnected".equals(chargingState);
    }
    /** 완전 충전됨: "Complete" 상태에서만 (다른 조건은 실패로 간주) */
    private boolean chargeDone() {
        return "Complete".equals(chargingState);
    }
    /** 충전 불가: 연결됐는데 충전중/완료 아님 (Stopped/NoPower/Starting 등) */
    private boolean chargeFailed() {
        return chargerConnected() && !stCharging && !chargeDone();
    }

    // 화면2(제어) 아이콘 + 상태 (null=미확인)
    private ImageView icDoor, icFrunk, icTrunk, icCharge, icSentry;
    private Boolean stLocked, stFrunkOpen, stTrunkOpen, stChargeOpen, stSentry;
    private boolean stSentryAvail = true;   // 센트리 켤 수 있음(저전력 모드면 false)

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

    private WaterOverlay waterOv;                            // 롱프레스 물 차오름

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        BASE = getString(R.string.bridge_base);
        KEY  = getString(R.string.bridge_key);
        prefs = getSharedPreferences("watch_prefs", MODE_PRIVATE);
        showRange = prefs.getBoolean("show_range", false);
        S = getResources().getDisplayMetrics().widthPixels / 480f;

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < 5; i++) {
            screen[i] = new FrameLayout(this);
            screen[i].setBackgroundColor(BG);   // 모든 화면 배경 통일 #161719
            screen[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            root.addView(screen[i], new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            if (i == 1) addBg(screen[i], "s2.png");   // 메인(0)/공조(2)/상태(3)/충전(4)은 각 build에서 처리
        }

        buildMain();
        buildControl();
        buildHvac();
        buildStatus();
        buildCharge();

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

        // 물 오버레이 (프렁크/트렁크 롱프레스 시 아래→위 차오름)
        waterOv = new WaterOverlay(this);
        waterOv.setVisibility(View.GONE);
        root.addView(waterOv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int start = deepLinkScreen(getIntent());   // 타일/딥링크로 특정 화면 진입
        show(start);
        fetchState();
        ui.postDelayed(refreshLoop, 60000);
    }

    /** Intent extra "screen"(0~4) 또는 data 경로(teslawatch://screen/N)로 진입 화면 결정 */
    private int deepLinkScreen(android.content.Intent it) {
        if (it == null) return 0;
        int s = it.getIntExtra("screen", -1);
        if (s < 0 && it.getData() != null) {
            try { s = Integer.parseInt(it.getData().getLastPathSegment()); } catch (Exception e) {}
        }
        return (s >= 0 && s <= 4) ? s : 0;
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(deepLinkScreen(intent));   // 이미 실행중이면 해당 화면으로 전환
    }

    // ── 화면 전환 ──
    private void show(int idx) {
        if (idx != 0) swBannerShown = false;   // 메인 재진입 시 배너 다시 표시 가능
        cur = idx;
        for (int i = 0; i < 5; i++) {
            if (i == idx) {
                if (i == 0) {
                    // 메인: bg는 즉시, 점선이 자라고 → 글자 페이드 인
                    screen[0].setAlpha(1f);
                    screen[0].setVisibility(View.VISIBLE);
                    startMainIntro();
                } else if (i == 4) {
                    // 충전: bg 즉시 → s5 페이드 → 점선/번개 → 글자 → 충전정보 페이드
                    screen[4].setAlpha(1f);
                    screen[4].setVisibility(View.VISIBLE);
                    startChargeIntro();
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
        // 서브화면에선 5초 폴링, 메인에선 정지
        ui.removeCallbacks(fastLoop);
        if (idx != 0) ui.postDelayed(fastLoop, 5000);

        if (idx == 0) { renderMain(); fetchState(); }
        if (idx == 1) renderControl();
        if (idx == 2) { renderHvac(); fetchState(); }   // 진입 시 공조상태 동기화(비깨움 GET)
        if (idx == 3) {                                  // 상태: 스크롤/페이드 초기화 후 동기화
            if (statScroll != null) statScroll.scrollTo(0, 0);
            if (statBg != null) statBg.setAlpha(1f);
            if (carName != null) carName.setAlpha(1f);
            renderStatus();
            fetchState();
        }
        if (idx == 4) { renderCharge(); fetchState(); }
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
            mainCarBg.setImageBitmap(retintDarkGray(BitmapFactory.decodeStream(is)));
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

        // 배터리 아이콘 + 숫자 영역 탭 → % ↔ km 토글 (라벨보다 먼저 추가하여 라벨이 위에)
        View battTap = new View(this);
        // 알약형 리플 (좌우 여백 있어 예쁘게 hugs the 배터리+숫자)
        battTap.setBackground(pillRipple(Math.round(8 * S), Math.round(6 * S)));
        battTap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleBattDisplay(); }
        });
        mainOverlay.addView(battTap, lp(180, 12, 120, 62));

        // 점선들 (차량 → 글자). 제어/상태는 아래→위, 공조/충전은 위→아래로 자람
        dashCtl  = new DashLine(this, false); mainOverlay.addView(dashCtl,  lp(78, 134, 4, 120));
        dashStat = new DashLine(this, false); mainOverlay.addView(dashStat, lp(374, 108, 4, 78));
        dashHvac = new DashLine(this, true);  mainOverlay.addView(dashHvac, lp(150, 307, 4, 88));
        dashChg  = new DashLine(this, true);  mainOverlay.addView(dashChg,  lp(352, 292, 4, 88));

        // 라벨 — 클릭영역 168×72 (가로 -5%, 세로 +20%), 리플은 글자 크기 알약형
        lblCtl  = label("제어", new Runnable(){ public void run(){ show(1); } });
        lblStat = label("상태", new Runnable(){ public void run(){ show(3); } });
        lblHvac = label("공조", new Runnable(){ public void run(){ show(2); } });
        lblChg  = label("충전", new Runnable(){ public void run(){ show(4); } });
        mainOverlay.addView(lblCtl,  lp(-4, 71, 168, 72));
        mainOverlay.addView(lblStat, lp(292, 44, 168, 72));
        mainOverlay.addView(lblHvac, lp(66, 400, 168, 72));
        mainOverlay.addView(lblChg,  lp(268, 384, 168, 72));

        // 가운데 원형 영역(차량 중앙) 탭 → 깨우기만 전송 (시각 표시 없음)
        View wakeTap = new View(this);
        wakeTap.setOnClickListener(new View.OnClickListener(){
            @Override public void onClick(View v){ wakeOnly(); }
        });
        mainOverlay.addView(wakeTap, lp(170, 168, 140, 140));

        // 하단 중앙 상태 아이콘 (sleep/wifi) — 공조 글자(32) 대비 2px 작은 높이 = 30
        statusIcon = new ImageView(this);
        statusIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        statusIcon.setColorFilter(C_OFF);
        int sz = 30;
        mainOverlay.addView(statusIcon, lp((480 - sz) / 2, 442, sz, sz));

        renderMain();
    }

    private void wakeOnly() {
        if (lastAwake) return;   // 이미 깨어있으면 전송 안 함
        toast("차량 깨우는 중…");
        new Thread(new Runnable(){
            @Override public void run(){
                try { httpPost(BASE + "/api/command/wake?key=" + KEY); } catch (Exception e) {}
                ui.postDelayed(new Runnable(){ public void run(){ fetchState(); } }, 4000);
            }
        }).start();
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
        dashCtl.setProgress(0f); dashStat.setProgress(0f); dashHvac.setProgress(0f); dashChg.setProgress(0f);
        lblCtl.setAlpha(0f); lblStat.setAlpha(0f); lblHvac.setAlpha(0f); lblChg.setAlpha(0f);
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
                dashCtl.setProgress(f); dashStat.setProgress(f); dashHvac.setProgress(f); dashChg.setProgress(f);
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                lblCtl.animate().alpha(1f).setDuration(400).start();
                lblStat.animate().alpha(1f).setDuration(400).start();
                lblHvac.animate().alpha(1f).setDuration(400).start();
                lblChg.animate().alpha(1f).setDuration(400).start();
                battery.animate().alpha(1f).setDuration(400).start();
                battText.animate().alpha(1f).setDuration(400).start();
            }
        });
        va.start();
    }

    private void renderMain() {
        if (battText == null) return;
        battText.setText(battLabel());
        int tint = stCharging ? C_GRN : (chargeFailed() ? C_YEL : C_PCT);
        battText.setTextColor(tint);
        if (battery != null) { battery.setLevel(lastSoc); battery.setTintOverride(tint == C_PCT ? 0 : tint); }
        if (statusIcon != null) statusIcon.setImageResource(lastAwake ? R.drawable.ic_wifi : R.drawable.ic_sleep);
        if (cur == 0 && swUpdateAvail && !swBannerShown) { swBannerShown = true; showSwBanner(); }
    }

    /** 신규 소프트웨어 배너 — 메인 상단, 2초 후 사라짐 */
    private void showSwBanner() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#cc2a2c2e"));   // 반투명 어두운 회색
        bg.setCornerRadius(26 * S);
        banner.setBackground(bg);
        int hp = Math.round(20 * S), vp = Math.round(12 * S);
        banner.setPadding(hp, vp, hp, vp);

        TextView tv = new TextView(this);
        tv.setText("신규 소프트웨어 ");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * S);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        banner.addView(tv);

        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_download);
        int isz = Math.round(26 * S);
        banner.addView(ic, new LinearLayout.LayoutParams(isz, isz));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Math.round(96 * S);
        final FrameLayout host = mainOverlay;
        banner.setAlpha(0f);
        host.addView(banner, lp);
        banner.animate().alpha(1f).setDuration(200).start();
        ui.postDelayed(new Runnable(){ @Override public void run(){
            banner.animate().alpha(0f).setDuration(250).withEndAction(new Runnable(){
                @Override public void run(){ host.removeView(banner); }
            }).start();
        }}, 2000);
    }

    private String battLabel() {
        if (showRange) return (lastRangeKm >= 0 ? lastRangeKm : "–") + "km";
        return (lastSoc >= 0 ? lastSoc : "–") + "%";
    }

    private void toggleBattDisplay() {
        showRange = !showRange;
        if (prefs != null) prefs.edit().putBoolean("show_range", showRange).apply();
        if (battText == null) return;
        battText.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
            @Override public void run() {
                battText.setText(battLabel());
                battText.animate().alpha(1f).setDuration(140).start();
            }
        }).start();
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
        // 잠금해제(=위험)는 길게, 잠금(=끄기)은 단일 클릭
        icDoor = iconSmartHold(R.drawable.ic_lock,
                new BoolFn(){ public boolean get(){ return tb(stLocked); } },
                new Runnable(){ public void run(){ toggleLock(); } });
        addIcon(controlOverlay, icDoor, 240, 240, 64);    // 정중앙
        // 충전구 열기는 길게, 닫기는 단일 클릭
        icCharge = iconSmartHold(R.drawable.ic_chargeport,
                new BoolFn(){ public boolean get(){ return !tb(stChargeOpen); } },
                new Runnable(){ public void run(){ toggleCharge(); } });
        addIcon(controlOverlay, icCharge, 92, 330, 56);   // 좌측 2/3 (+10 down)
        // 센트리 켜기는 길게, 끄기는 단일 클릭
        icSentry = iconSmartHold(R.drawable.ic_sentry,
                new BoolFn(){ public boolean get(){ return !tb(stSentry); } },
                new Runnable(){ public void run(){ toggleSentry(); } });
        addIcon(controlOverlay, icSentry, 380, 220, 52);  // 우측 (+10 right, -20 up)
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
        final ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(C_OFF);
        iv.setBackground(circleRipple());
        attachHold(iv, new Runnable() {
            @Override public void run() { showConfirmCard(what, label, path); }
        }, true);
        return iv;
    }

    /** 롱프레스 홀드로 즉시 실행 (확인창 없음). 짧게 뗄 때는 안내 알럿 표시. */
    private ImageView iconHold(int res, final Runnable onComplete) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(C_OFF);
        iv.setBackground(circleRipple());
        attachHold(iv, onComplete, true);
        return iv;
    }

    private interface BoolFn { boolean get(); }

    /** 조건부: needsHold=true면 길게 눌러 실행(켜기), false면 단일 클릭 즉시 실행(끄기·알럿없음) */
    private ImageView iconSmartHold(int res, final BoolFn needsHold, final Runnable action) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(C_OFF);
        iv.setBackground(circleRipple());
        attachSmartHold(iv, needsHold, action);
        return iv;
    }

    private void attachSmartHold(View v, final BoolFn needsHold, final Runnable action) {
        v.setOnTouchListener(new View.OnTouchListener() {
            boolean holdMode = false;
            final boolean[] completed = {false};
            @Override public boolean onTouch(View vv, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        vv.setPressed(true);
                        holdMode = needsHold.get();
                        completed[0] = false;
                        if (holdMode) waterOv.start(new Runnable() {
                            @Override public void run() { completed[0] = true; action.run(); }
                        });
                        return true;
                    case MotionEvent.ACTION_UP:
                        vv.setPressed(false);
                        if (holdMode) {
                            if (!completed[0]) { waterOv.cancel(); showCenterAlert("길게 눌러서 실행"); }
                            // 완료됐으면 물은 이미 자동 사라짐 + 알럿 없음
                        } else {
                            action.run();   // 끄기: 단일 클릭 즉시
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        vv.setPressed(false);
                        if (holdMode && !completed[0]) waterOv.cancel();
                        return true;
                }
                return false;
            }
        });
    }

    /** 뷰에 물 차오름 홀드 동작 부착. showAlertOnFail=true면 짧게 뗄 때 "길게 눌러서 실행" 표시 */
    private void attachHold(View v, final Runnable onComplete, final boolean showAlertOnFail) {
        v.setOnTouchListener(new View.OnTouchListener() {
            final boolean[] completed = {false};
            @Override public boolean onTouch(View vv, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        vv.setPressed(true);
                        completed[0] = false;
                        waterOv.start(new Runnable() {
                            @Override public void run() { completed[0] = true; onComplete.run(); }
                        });
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        vv.setPressed(false);
                        if (!completed[0]) {
                            waterOv.cancel();
                            if (showAlertOnFail && e.getActionMasked() == MotionEvent.ACTION_UP)
                                showCenterAlert("길게 눌러서 실행");
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /** 커스텀 확인 카드 — 검정 라운드 배경, 알약 버튼 */
    private void showConfirmCard(String what, final String label, final String path) {
        final FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(Color.parseColor("#b0000000"));
        dim.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(Color.parseColor("#1e1f21"));
        cbg.setCornerRadius(28 * S);
        card.setBackground(cbg);
        int pad = Math.round(22 * S);
        card.setPadding(pad, pad, pad, pad);

        TextView msg = new TextView(this);
        msg.setText(what + " 여시겠습니까?");
        msg.setTextColor(Color.parseColor("#f2f2f2"));
        msg.setGravity(Gravity.CENTER);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26 * S);
        msg.setTypeface(msg.getTypeface(), Typeface.BOLD);
        card.addView(msg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(18 * S);
        card.addView(btnRow, rowLp);

        TextView cancelBtn = pillBtn("취소", Color.parseColor("#3a3b3d"), Color.parseColor("#f2f2f2"));
        TextView okBtn     = pillBtn("확인", Color.parseColor("#e8501c"), Color.WHITE);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                0, Math.round(58 * S), 1f);
        bLp.setMargins(Math.round(5 * S), 0, Math.round(5 * S), 0);
        btnRow.addView(cancelBtn, bLp);
        btnRow.addView(okBtn, bLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.round(360 * S), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        dim.addView(card, cardLp);

        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        dim.animate().alpha(1f).setDuration(180).start();

        final Runnable dismiss = new Runnable() {
            @Override public void run() {
                dim.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
                    @Override public void run() { root.removeView(dim); }
                }).start();
            }
        };
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss.run(); waterOv.fadeOut(); }
        });
        okBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss.run(); waterOv.fadeOut(); cmd(path, label); }
        });
    }

    /** 상단에 잠깐 뜨는 인라인 알림 (온도 조절 힌트 등) */
    private void showTopAlert(String msg) {
        showAlertAt(msg, Gravity.TOP | Gravity.CENTER_HORIZONTAL, Math.round(50 * S));
    }

    /** 중앙에 잠깐 뜨는 인라인 알림 (Toast 대신 커스텀 라운드 카드) */
    private void showCenterAlert(String msg) {
        showAlertAt(msg, Gravity.CENTER, 0);
    }

    private void showAlertAt(String msg, int gravity, int topMargin) {
        final TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(Color.parseColor("#f2f2f2"));
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * S);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        int hpad = Math.round(28 * S), vpad = Math.round(18 * S);
        tv.setPadding(hpad, vpad, hpad, vpad);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#d8000000"));
        d.setCornerRadius(28 * S);
        tv.setBackground(d);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
        lp.topMargin = topMargin;
        tv.setAlpha(0f);
        root.addView(tv, lp);
        tv.animate().alpha(1f).setDuration(150).start();

        ui.postDelayed(new Runnable() {
            @Override public void run() {
                tv.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                    @Override public void run() { root.removeView(tv); }
                }).start();
            }
        }, 1200);
    }

    private TextView pillBtn(String text, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * S);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(30 * S);
        t.setBackground(d);
        t.setClickable(true);
        return t;
    }

    /** 화면 전체를 덮는 물 오버레이 — 아래→위로 차오르고 sine wave로 살짝 출렁 */
    private class WaterOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress = 0f;    // 0=비어있음 1=가득
        private float phase = 0f;       // 파도 위상
        private ValueAnimator fillAnim, waveAnim;
        private Runnable onComplete;

        WaterOverlay(Context ctx) {
            super(ctx);
            paint.setColor(Color.parseColor("#99b8bab9"));   // 반투명 연회색
            paint.setStyle(Paint.Style.FILL);
        }

        void start(final Runnable done) {
            onComplete = done;
            setVisibility(View.VISIBLE);
            setAlpha(1f);
            progress = 0f;
            if (fillAnim != null) fillAnim.cancel();
            fillAnim = ValueAnimator.ofFloat(0f, 1f);
            fillAnim.setDuration(1200);
            fillAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    progress = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            fillAnim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    if (progress >= 0.99f && onComplete != null) {
                        Runnable r = onComplete; onComplete = null; r.run();
                        fadeOut();   // 완료 시 자동으로 물 사라짐 (busy로 ACTION_UP 못 받는 경우 대비)
                    }
                }
            });
            fillAnim.start();

            if (waveAnim == null || !waveAnim.isRunning()) {
                waveAnim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
                waveAnim.setDuration(1400);
                waveAnim.setRepeatCount(ValueAnimator.INFINITE);
                waveAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        phase = (Float) a.getAnimatedValue();
                        if (progress > 0f) invalidate();
                    }
                });
                waveAnim.start();
            }
        }

        void cancel() {
            if (fillAnim != null) fillAnim.cancel();
            onComplete = null;
            fadeOut();
        }

        void fadeOut() {
            animate().alpha(0f).setDuration(280).withEndAction(new Runnable() {
                @Override public void run() {
                    setVisibility(View.GONE);
                    progress = 0f;
                    if (waveAnim != null) { waveAnim.cancel(); waveAnim = null; }
                }
            }).start();
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (progress <= 0f) return;
            int w = getWidth(), h = getHeight();
            float top = h * (1f - progress);
            float amp = 6f * S;
            Path p = new Path();
            p.moveTo(0, top);
            for (int x = 0; x <= w; x += 6) {
                float y = top + amp * (float) Math.sin((x / (float) w) * 4 * Math.PI + phase);
                p.lineTo(x, y);
            }
            p.lineTo(w, h);
            p.lineTo(0, h);
            p.close();
            cv.drawPath(p, paint);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void renderControl() {
        if (icDoor == null) return;
        // 잠김 → 자물쇠 아이콘(회색), 잠금해제 → 열린 자물쇠 아이콘(노랑)
        boolean unlocked = (stLocked != null && !stLocked);
        icDoor.setImageResource(unlocked ? R.drawable.ic_unlock : R.drawable.ic_lock);
        icDoor.setColorFilter(unlocked ? C_YEL : C_OFF);
        icFrunk.setColorFilter(tb(stFrunkOpen) ? C_WHT : C_OFF);
        icTrunk.setColorFilter(tb(stTrunkOpen) ? C_WHT : C_OFF);
        icCharge.setColorFilter(tb(stChargeOpen) ? C_GRN : C_OFF);
        if (icSentry != null) icSentry.setColorFilter(tb(stSentry) ? C_LOW : C_OFF);
    }

    private void toggleLock() {
        boolean locked = tb(stLocked);
        cmd("/api/command/" + (locked ? "unlock" : "lock"), locked ? "잠금 해제" : "잠금");
    }

    private void toggleCharge() {
        boolean open = tb(stChargeOpen);
        cmd("/api/command/" + (open ? "charge_port_close" : "charge_port_open"), open ? "충전구 닫기" : "충전구 열기");
    }

    private void toggleSentry() {
        boolean on = tb(stSentry);
        if (!on && !stSentryAvail) {   // 켜려는데 저전력 모드면 불가
            showCenterAlert("저전력 모드입니다\n센트리를 켤 수 없습니다");
            return;
        }
        stSentry = !on;
        renderControl();
        cmd("/api/sentry?on=" + (!on), on ? "센트리 OFF" : "센트리 ON");
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
        // 켜기는 길게(공조 OFF), 끄기는 단일 클릭(공조 ON)
        attachSmartHold(tempText,
                new BoolFn(){ public boolean get(){ return !stClimate; } },
                new Runnable(){ public void run(){ if (stClimate) climateOff(); else applyClimate(); } });
        hvacOverlay.addView(tempText, lp(180, 393, 120, 44));

        // 힌트: 공조 OFF일 때만 "길게 눌러서 켜기"
        hintText = new TextView(this);
        hintText.setText("길게 눌러서 켜기");
        hintText.setTextColor(C_ARROW);
        hintText.setGravity(Gravity.CENTER);
        hintText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12 * S);
        hintText.setVisibility(View.GONE);
        hvacOverlay.addView(hintText, lp(120, 440, 240, 20));

        // 온도 내림(좌, 180° 뒤집힘) / 올림(우, 정방향) — 클릭 영역 46×46, 시각은 40×40 유지
        icArrowDown = arrowBtn(true);
        hvacOverlay.addView(icArrowDown, lp(127, 392, 46, 46));
        icArrowUp = arrowBtn(false);
        hvacOverlay.addView(icArrowUp, lp(307, 392, 46, 46));

        renderHvac();
    }

    private ImageView arrowBtn(final boolean down) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_arrow);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(C_ARROW);
        int pad = Math.round(3 * S);   // 클릭 영역 46 - 시각 40 = 6 → padding 3
        iv.setPadding(pad, pad, pad, pad);
        if (down) iv.setRotation(180);
        iv.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View v){
            adjustTemp(down ? -1 : 1);
            if (stClimate) sendTemp(tempSet);
            else showTopAlert("온도를 길게\n눌러서 공조 켜기");
        } });
        return iv;
    }

    /** 공조 켜진 상태에서 온도 조절 즉시 전송 (Wake 없이 바로 set_temp) */
    private void sendTemp(final int t) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { httpPost(BASE + "/api/set_temp?celsius=" + t + "&key=" + KEY); } catch (Exception e) {}
            }
        }).start();
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
        if (!beginCmd()) return;
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
                    endCmd();
                    ui.postDelayed(new Runnable(){ public void run(){ fetchState(); } }, 2500);
                }
            }
        }).start();
    }

    private void climateOff() {
        if (!beginCmd()) return;
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
                    endCmd();
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
        if (hintText != null) hintText.setVisibility(stClimate ? View.GONE : View.VISIBLE);
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
        s.setBackgroundColor(BG);

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

    // ═══════════ 화면5: 충전 ═══════════
    private void buildCharge() {
        FrameLayout s = screen[4];
        // 맨 아래: bg.png (즉시), 그 위 s5.png (첫 애니 페이드)
        addBg(s, "bg.png");
        chgCarBg = new ImageView(this);
        chgCarBg.setScaleType(ImageView.ScaleType.FIT_XY);
        chgCarBg.setAlpha(0f);
        Bitmap bm = loadAsset("s5.png");
        if (bm != null) chgCarBg.setImageBitmap(bm);
        s.addView(chgCarBg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 충전구 → 번개까지 가로 점선 (우→좌)
        dashOpen = new DashLine(this, DashLine.H_RL);
        s.addView(dashOpen, lp(112, 150, 145, 30));

        // 번개(충전 잠금해제) 아이콘
        icOpen = new ImageView(this);
        icOpen.setImageResource(R.drawable.ic_open);
        icOpen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icOpen.setColorFilter(C_WHT);
        icOpen.setAlpha(0f);
        icOpen.setBackground(circleRipple());
        icOpen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleCharge(); }
        });
        s.addView(icOpen, lp(58, 143, 56, 56));

        // 충전 정보 오버레이 (애니 마지막 단계에서 페이드)
        chgOverlay = new FrameLayout(this);
        chgOverlay.setAlpha(0f);
        s.addView(chgOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // SOC %
        chgSoc = new TextView(this);
        chgSoc.setTextColor(Color.WHITE);
        chgSoc.setGravity(Gravity.LEFT | Gravity.BOTTOM);
        chgSoc.setTypeface(chgSoc.getTypeface(), android.graphics.Typeface.BOLD);
        chgSoc.setTextSize(TypedValue.COMPLEX_UNIT_PX, 42 * S);
        chgSoc.setText("–%");
        chgOverlay.addView(chgSoc, lp(50, 258, 150, 54));

        // 주행가능 거리 (글자 20% 크게: 19→23)
        chgRange = new TextView(this);
        chgRange.setTextColor(Color.parseColor("#c8cacb"));
        chgRange.setGravity(Gravity.LEFT | Gravity.BOTTOM);
        chgRange.setTextSize(TypedValue.COMPLEX_UNIT_PX, 23 * S);
        chgRange.setText("– km 주행가능");
        // 하단정렬 맞춤: chgSoc 하단(258+54=312)과 동일하게 chgRange bottom = 312
        chgOverlay.addView(chgRange, lp(178, 278, 300, 34));

        // 게이지 + 드래그 흰 원 (너비 10%↓: 424→382, 중앙 유지 x=49, y -20)
        chargeBar = new ChargeBar(this);
        chargeBar.setListeners(new Runnable() {
            @Override public void run() { chgDragging = true; chgLimitSel = chargeBar.getLimit(); updateLimitLabel(); }
        }, new Runnable() {
            @Override public void run() { chgDragging = false; setChargeLimit(chargeBar.getLimit()); }
        });
        chgOverlay.addView(chargeBar, lp(49, 316, 382, 44));

        // 흰 원 아래 제한 % 텍스트 (크기 18→24)
        chgLimitTxt = new TextView(this);
        chgLimitTxt.setTextColor(Color.WHITE);
        chgLimitTxt.setGravity(Gravity.CENTER);
        chgLimitTxt.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * S);
        chgLimitTxt.setText("80%");
        chgOverlay.addView(chgLimitTxt, lp(0, 354, 80, 30));

        // 완충 예상 시각 (하단 중앙) — 크기 21→23 (10%), y 424→414
        chgEta = new TextView(this);
        chgEta.setTextColor(Color.WHITE);
        chgEta.setGravity(Gravity.CENTER);
        chgEta.setTextSize(TypedValue.COMPLEX_UNIT_PX, 23 * S);
        chgEta.setText("");
        chgOverlay.addView(chgEta, lp(20, 414, 440, 32));

        renderCharge();
    }

    private final Runnable chargeIntroRun = new Runnable() { @Override public void run() { playChargeIntro(); } };

    private void startChargeIntro() {
        if (chgCarBg == null) return;
        chgCarBg.setAlpha(0f);
        dashOpen.setProgress(0f);
        icOpen.setAlpha(0f);
        chgOverlay.setAlpha(0f);
        screen[4].post(chargeIntroRun);
    }

    private void playChargeIntro() {
        // 1) s5 페이드 → 2) 점선 성장 → 3) 번개 페이드 → 4) 충전정보 페이드
        chgCarBg.animate().alpha(1f).setDuration(300).withEndAction(new Runnable() {
            @Override public void run() {
                ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(650);
                va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        dashOpen.setProgress(((Float) a.getAnimatedValue()).floatValue());
                    }
                });
                va.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        icOpen.animate().alpha(1f).setDuration(400).start();
                        chgOverlay.animate().alpha(1f).setStartDelay(200).setDuration(400)
                                .withEndAction(new Runnable(){ @Override public void run(){ updateLimitLabel(); } })
                                .start();
                    }
                });
                va.start();
            }
        }).start();
    }

    private void updateLimitLabel() {
        if (chgLimitTxt == null || chargeBar == null) return;
        chgLimitTxt.setText(chargeBar.getLimit() + "%");
        float x = chargeBar.getX() + chargeBar.knobCenterX() - Math.round(40 * S);
        if (chargeBar.getWidth() > 0) chgLimitTxt.setX(x);
    }

    private void renderCharge() {
        renderChargeText();
        renderChargeBar();
    }

    /** 슬라이더 포함 전체 갱신 (화면 진입 시) */
    private void renderChargeBar() {
        if (chargeBar == null) return;
        chargeBar.setSoc(lastSoc >= 0 ? lastSoc : 0);
        if (chgLimitSel < 50) chgLimitSel = lastLimit;
        chargeBar.setLimit(lastLimit);
        chgLimitSel = lastLimit;
        chargeBar.post(new Runnable(){ @Override public void run(){ updateLimitLabel(); } });
    }

    /** 텍스트만 갱신 (충전화면 표시 중에도 안전 — 슬라이더 드래그 위치 유지) */
    private void renderChargeText() {
        if (chgSoc == null) return;
        if (icOpen != null) icOpen.setColorFilter(tb(stChargeOpen) ? C_GRN : C_WHT);
        chgSoc.setText((lastSoc >= 0 ? lastSoc : "–") + "%");
        chgRange.setText((lastRangeKm >= 0 ? lastRangeKm : "–") + " km 주행가능");
        boolean connected = chargerConnected();
        if (chargeBar != null) {
            chargeBar.setSoc(lastSoc >= 0 ? lastSoc : 0);
            chargeBar.setShowKnob(connected);      // 충전기 연결 시에만 제한선(노란원)
            chargeBar.setCharging(stCharging);      // 충전 중 게이지 애니메이션
            if (!chgDragging) { chargeBar.setLimit(lastLimit); chgLimitSel = lastLimit; }  // 차량 실제값 반영
        }
        if (chgLimitTxt != null) {
            chgLimitTxt.setVisibility(connected ? View.VISIBLE : View.GONE);
            updateLimitLabel();
        }
        if (stCharging) {
            chgEta.setText(chargeEtaText(lastMinToFull));
            chgEta.setTextSize(TypedValue.COMPLEX_UNIT_PX, 23 * S);
            chgEta.setTextColor(Color.WHITE);
        } else if (chargeDone()) {
            chgEta.setText("충전 완료");
            chgEta.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * S);
            chgEta.setTextColor(Color.WHITE);
        } else if (chargeFailed()) {
            chgEta.setText("충전 불가");
            chgEta.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * S);
            chgEta.setTextColor(C_YEL);
        } else {
            chgEta.setText("");
            chgEta.setTextColor(Color.WHITE);
        }
        chargeBar.post(new Runnable(){ @Override public void run(){ updateLimitLabel(); } });
    }

    private void setChargeLimit(final int pct) {
        lastLimit = pct;
        chgAttemptTs = System.currentTimeMillis();
        cmd("/api/charge_limit?percent=" + pct, "충전 한도 " + pct + "%");
    }

    private String chargeEtaText(int minutes) {
        if (minutes <= 0) return "";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar fin = (java.util.Calendar) now.clone();
        fin.add(java.util.Calendar.MINUTE, minutes);
        java.util.Calendar d0 = (java.util.Calendar) now.clone();
        java.util.Calendar d1 = (java.util.Calendar) fin.clone();
        for (java.util.Calendar c : new java.util.Calendar[]{d0, d1}) {
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        }
        long days = Math.round((d1.getTimeInMillis() - d0.getTimeInMillis()) / 86400000.0);
        String day = days <= 0 ? "오늘" : days == 1 ? "다음날" : days == 2 ? "모레"
                : (fin.get(java.util.Calendar.MONTH) + 1) + "/" + fin.get(java.util.Calendar.DAY_OF_MONTH);
        String hh = String.format("%02d", fin.get(java.util.Calendar.HOUR_OF_DAY));
        String mm = String.format("%02d", fin.get(java.util.Calendar.MINUTE));
        return day + " " + hh + ":" + mm + "에 충전 완료";
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

    /** 이미지의 어두운 회색 배경(#101010~#181820 범위)을 #161719로 치환 */
    private Bitmap retintDarkGray(Bitmap src) {
        if (src == null) return null;
        try {
            Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
            int w = out.getWidth(), h = out.getHeight();
            int[] px = new int[w * h];
            out.getPixels(px, 0, w, 0, 0, w, h);
            final int TR = 0x16, TG = 0x17, TB = 0x19;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int a = (c >>> 24) & 0xff;
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                // 어두운 무채색(R/G/B ≤ 30, 서로 차이 8 이내)만 치환 — 그림자 등 보호
                if (a > 0 && r <= 30 && g <= 30 && b <= 30
                        && Math.abs(r - g) < 8 && Math.abs(g - b) < 8 && Math.abs(r - b) < 8) {
                    px[i] = (a << 24) | (TR << 16) | (TG << 8) | TB;
                }
            }
            out.setPixels(px, 0, w, 0, 0, w, h);
            return out;
        } catch (Exception e) { return src; }
    }

    private Bitmap loadAsset(String name) {
        InputStream is = null;
        try {
            is = getAssets().open(name);
            return retintDarkGray(BitmapFactory.decodeStream(is));
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
    /** 서브화면(제어/공조/상태/충전) 표시 중 5초마다 상태 새로 가져오기 */
    private final Runnable fastLoop = new Runnable() {
        @Override public void run() {
            fetchState();
            if (cur != 0) ui.postDelayed(this, 5000);
        }
    };

    private void fetchState() {
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject resp = null;
                boolean cached = true;
                try {
                    String body = httpGet(BASE + "/api/state?key=" + KEY);
                    JSONObject j = new JSONObject(body);
                    resp = j.optJSONObject("response");
                    cached = j.optBoolean("cached", true);
                } catch (Exception e) {
                    // 무시
                }
                final JSONObject fresp = resp;
                final boolean fawake = (resp != null && !cached);
                ui.post(new Runnable() {
                    @Override public void run() { lastAwake = fawake; applyState(fresp); renderMain(); }
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
            if (cs.has("battery_range")) lastRangeKm = (int) Math.round(cs.optDouble("battery_range", 0) * MI);
            if (cs.has("charge_port_door_open")) stChargeOpen = cs.optBoolean("charge_port_door_open");
            if (cs.has("charge_limit_soc") && !chgDragging) lastLimit = cs.optInt("charge_limit_soc", lastLimit);
            if (cs.has("minutes_to_full_charge")) lastMinToFull = cs.optInt("minutes_to_full_charge", -1);
            if (cs.has("charging_state")) {
                chargingState = cs.optString("charging_state", "");
                stCharging = "Charging".equals(chargingState);
                stChargeComplete = "Complete".equals(chargingState);
            }
        }
        if (vs != null) {
            if (vs.has("locked")) stLocked = vs.optBoolean("locked");
            if (vs.has("ft")) stFrunkOpen = vs.optInt("ft", 0) != 0;
            if (vs.has("rt")) stTrunkOpen = vs.optInt("rt", 0) != 0;
            if (vs.has("sentry_mode")) stSentry = vs.optBoolean("sentry_mode");
            if (vs.has("sentry_mode_available")) stSentryAvail = vs.optBoolean("sentry_mode_available", true);
            JSONObject su = vs.optJSONObject("software_update");
            String sust = su != null ? su.optString("status", "") : "";
            swUpdateAvail = sust != null && !sust.isEmpty() && !"unavailable".equals(sust);
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
        if (cur != 4) renderCharge();
        else renderChargeText();          // 충전화면 표시 중엔 텍스트만 (슬라이더 위치 유지)
        if (cmdBusy) applyBusyTint();     // 전송중이면 회색 유지
    }

    // ── 명령 전송중 잠금 ──
    private boolean beginCmd() {
        if (cmdBusy) return false;
        cmdBusy = true;
        ui.post(new Runnable(){ public void run(){ setCommandsBusy(true); } });
        return true;
    }
    private void endCmd() {
        cmdBusy = false;
        ui.post(new Runnable(){ public void run(){ setCommandsBusy(false); } });
    }
    private void setCommandsBusy(boolean busy) {
        View[] all = { icDoor, icFrunk, icTrunk, icCharge, icSentry, icWheel, icArrowUp, icArrowDown,
                       icOpen, seatIcon[0], seatIcon[1], seatIcon[2], seatIcon[3], seatIcon[4],
                       tempText, chargeBar };
        for (View v : all) if (v != null) v.setEnabled(!busy);
        if (busy) {
            applyBusyTint();
        } else {
            renderControl(); renderHvac();
            if (cur == 4) renderChargeText(); else renderCharge();
        }
    }
    private void applyBusyTint() {
        ImageView[] tint = { icDoor, icFrunk, icTrunk, icCharge, icSentry, icWheel, icArrowUp, icArrowDown,
                             icOpen, seatIcon[0], seatIcon[1], seatIcon[2], seatIcon[3], seatIcon[4] };
        for (ImageView iv : tint) if (iv != null) iv.setColorFilter(C_BUSY);
        if (tempText != null) tempText.setTextColor(C_BUSY);
    }

    private void cmd(final String path, final String label) {
        if (!beginCmd()) return;   // 이미 전송중이면 무시
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
                    endCmd();
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
        private int tintOverride = 0;   // 0=기본색, 그 외=강제 색상
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);

        BatteryView(Context ctx) {
            super(ctx);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(C_OUT);
            fill.setStyle(Paint.Style.FILL);
        }

        void setLevel(int l) { level = l; invalidate(); }
        void setTintOverride(int c) {
            if (tintOverride != c) { tintOverride = c; invalidate(); }
        }

        @Override protected void onDraw(Canvas cv) {
            float sx = getWidth() / 48f, sy = getHeight() / 24f;
            stroke.setStrokeWidth(2 * sx);
            stroke.setColor(tintOverride != 0 ? tintOverride : C_OUT);
            cv.drawRoundRect(1 * sx, 4 * sy, 41 * sx, 20 * sy, 3 * sx, 3 * sx, stroke);
            fill.setColor(tintOverride != 0 ? tintOverride : C_OUT);
            cv.drawRoundRect(43 * sx, 9 * sy, 46.5f * sx, 15 * sy, 1 * sx, 1 * sx, fill);
            if (level >= 0) {
                fill.setColor(tintOverride != 0 ? tintOverride : (level <= 15 ? C_LOW : C_FILL));
                float w = 34f * Math.max(0, Math.min(100, level)) / 100f;
                if (w > 0) cv.drawRoundRect(4 * sx, 7 * sy, (4 + w) * sx, 17 * sy, 1.5f * sx, 1.5f * sx, fill);
            }
        }
    }

    /** 수직 점선 (progress로 자라나는 애니메이션) */
    private class DashLine extends View {
        static final int V_DOWN = 0, V_UP = 1, H_RL = 2;   // 세로↓ / 세로↑ / 가로 우→좌
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mode;
        private float prog = 1f;
        DashLine(Context ctx, boolean downward) { this(ctx, downward ? V_DOWN : V_UP); }
        DashLine(Context ctx, int mode) {
            super(ctx);
            this.mode = mode;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(C_DASH);
            p.setStrokeWidth(2 * S);
            p.setPathEffect(new DashPathEffect(new float[]{6 * S, 6 * S}, 0));
        }
        void setProgress(float f) { prog = f; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            if (prog <= 0f) return;
            float x = getWidth() / 2f, h = getHeight(), w = getWidth(), y = getHeight() / 2f;
            if (mode == V_DOWN)      cv.drawLine(x, 0, x, h * prog, p);
            else if (mode == V_UP)   cv.drawLine(x, h, x, h - h * prog, p);
            else                     cv.drawLine(w, y, w - w * prog, y, p);   // H_RL
        }
    }

    // ═══════════ 충전 게이지 (초록 잔량 + 드래그 노란 제한원) ═══════════
    private class ChargeBar extends View {
        private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pFill  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pKnob  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pSpark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int soc = 0, limit = 80;
        private boolean showKnob = false;     // 충전기 연결 시에만 노란원 표시·조작
        private boolean charging = false;
        private float sparkT = 0f;            // 0=우(100%) 1=좌(soc경계)
        private ValueAnimator sparkAnim;
        private Runnable onLimitChange, onLimitCommit;
        ChargeBar(Context ctx) {
            super(ctx);
            pTrack.setColor(Color.parseColor("#3a3d40"));
            pFill.setColor(C_GRN);
            pKnob.setColor(Color.WHITE);
            pSpark.setColor(C_GRN);
            pSpark.setStrokeCap(Paint.Cap.ROUND);
        }
        void setSoc(int s) { soc = s; invalidate(); }
        void setLimit(int l) { limit = Math.max(50, Math.min(100, l)); invalidate(); }
        int getLimit() { return limit; }
        void setShowKnob(boolean b) { if (showKnob != b) { showKnob = b; invalidate(); } }
        void setCharging(boolean c) {
            if (charging == c) return;
            charging = c;
            if (c) startSpark(); else stopSpark();
            invalidate();
        }
        private void startSpark() {
            if (sparkAnim != null) return;
            // 0~1 이동, 1~1.6 구간은 정지(패스 사이 간격) → 선은 draw에서 sparkT<=1일 때만
            sparkAnim = ValueAnimator.ofFloat(0f, 1.6f);
            sparkAnim.setDuration(2200);
            sparkAnim.setRepeatCount(ValueAnimator.INFINITE);
            sparkAnim.setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f));
            sparkAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) { sparkT = (Float) a.getAnimatedValue(); invalidate(); }
            });
            sparkAnim.start();
        }
        private void stopSpark() {
            if (sparkAnim != null) { sparkAnim.cancel(); sparkAnim = null; }
        }
        void setListeners(Runnable change, Runnable commit) { onLimitChange = change; onLimitCommit = commit; }

        private float padL() { return 26 * S; }
        private float padR() { return 26 * S; }
        private float trackW() { return getWidth() - padL() - padR(); }
        private float barY() { return 20 * S; }
        private float xForPct(float pct) { return padL() + trackW() * pct / 100f; }

        @Override protected void onDraw(Canvas cv) {
            float y = barY(), r = 5 * S;
            cv.drawRoundRect(padL(), y - r, padL() + trackW(), y + r, r, r, pTrack);
            cv.drawRoundRect(padL(), y - r, xForPct(soc), y + r, r, r, pFill);
            // 충전 중: 회색(빈) 구간에서 초록 세로선이 100%→soc경계로 우→좌 이동 (sparkT>1은 정지 간격)
            if (charging && soc < 100 && sparkT <= 1f) {
                float xRight = xForPct(100), xLeft = xForPct(soc);
                float x = xRight + (xLeft - xRight) * sparkT;
                pSpark.setStrokeWidth(3 * S);
                cv.drawLine(x, y - r, x, y + r, pSpark);
            }
            if (showKnob) cv.drawCircle(xForPct(limit), y, 13 * S, pKnob);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!showKnob) return false;   // 충전기 미연결 시 제한 조작 불가
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    float pct = (e.getX() - padL()) / trackW() * 100f;
                    int nl = Math.max(50, Math.min(100, Math.round(pct)));
                    if (nl != limit) { limit = nl; invalidate(); if (onLimitChange != null) onLimitChange.run(); }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (onLimitCommit != null) onLimitCommit.run();
                    return true;
            }
            return super.onTouchEvent(e);
        }
        float knobCenterX() { return xForPct(limit); }
    }
}
