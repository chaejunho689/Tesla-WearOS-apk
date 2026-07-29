package com.hongcha.teslawatch;

import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.LayoutElementBuilders.Box;
import androidx.wear.protolayout.LayoutElementBuilders.Image;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.LayoutElementBuilders.Text;
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ModifiersBuilders.Clickable;
import androidx.wear.protolayout.ModifiersBuilders.Modifiers;
import androidx.wear.protolayout.ModifiersBuilders.Padding;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;

import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/** 메인 화면 타일(위젯): 배경 + 공조/상태/제어/충전 클릭 → 앱 해당 화면 딥링크 */
public class MainTileService extends TileService {

    private static final String RES_VER = "1";
    private static final String PKG = "com.hongcha.teslawatch";
    private static final String ACT = "com.hongcha.teslawatch.MainActivity";

    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest request) {
        LayoutElementBuilders.Layout layout =
                new LayoutElementBuilders.Layout.Builder().setRoot(buildRoot()).build();
        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VER)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(buildRoot()))
                .build();
        return Futures.immediateFuture(tile);
    }

    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            RequestBuilders.ResourcesRequest request) {
        ResourceBuilders.Resources res = new ResourceBuilders.Resources.Builder()
                .setVersion(RES_VER)
                .addIdToImageMapping("bg", new ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                                new ResourceBuilders.AndroidImageResourceByResId.Builder()
                                        .setResourceId(R.drawable.tile_bg).build())
                        .build())
                .build();
        return Futures.immediateFuture(res);
    }

    // 클릭영역 크기(사분면 대비 비율) — 글자 정도로 작게
    private static final float BW = 0.55f, BH = 0.30f;

    private LayoutElement buildRoot() {
        // 메인 시안 이미지(점선·글자 포함)를 배경으로, 위에 라벨 위치에 맞춘 클릭영역
        return new Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .addContent(bgImage())
                .addContent(new LayoutElementBuilders.Column.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .setHeight(DimensionBuilders.expand())
                        .addContent(new LayoutElementBuilders.Row.Builder()
                                .setWidth(DimensionBuilders.expand())
                                .setHeight(weight(0.5f))
                                // 좌상=제어(라벨 상대위치 0.33,0.45) / 우상=상태(0.57,0.33)
                                .addContent(cell(1, 0.333f, 0.446f))
                                .addContent(cell(3, 0.567f, 0.333f))
                                .build())
                        .addContent(new LayoutElementBuilders.Row.Builder()
                                .setWidth(DimensionBuilders.expand())
                                .setHeight(weight(0.5f))
                                // 좌하=공조(0.63,0.82) / 우하=충전(0.47,0.75)
                                .addContent(cell(2, 0.625f, 0.800f))
                                .addContent(cell(4, 0.467f, 0.750f))
                                .build())
                        .build())
                .build();
    }

    private LayoutElement bgImage() {
        return new Image.Builder()
                .setResourceId("bg")
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_CROP)
                .build();
    }

    /** 사분면(cell) 안에서 라벨 위치(relX,relY, 0~1)에 작은 클릭영역 배치 */
    private LayoutElement cell(int screen, float relX, float relY) {
        float left = clamp(relX - BW / 2), right = clamp(1 - relX - BW / 2);
        float top = clamp(relY - BH / 2),  bottom = clamp(1 - relY - BH / 2);

        LayoutElement clickBox = new Box.Builder()
                .setWidth(weight(BW))
                .setHeight(DimensionBuilders.expand())
                .setModifiers(new Modifiers.Builder().setClickable(launch(screen)).build())
                .build();

        LayoutElementBuilders.Row midRow = new LayoutElementBuilders.Row.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(weight(BH))
                .addContent(hSpacer(left))
                .addContent(clickBox)
                .addContent(hSpacer(right))
                .build();

        return new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())   // 사분면 = 가로 절반
                .setHeight(DimensionBuilders.expand())
                .addContent(vSpacer(top))
                .addContent(midRow)
                .addContent(vSpacer(bottom))
                .build();
    }

    private Clickable launch(int screen) {
        return new Clickable.Builder()
                .setId("s" + screen)
                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(PKG)
                                .setClassName(ACT)
                                .addKeyToExtraMapping("screen",
                                        new ActionBuilders.AndroidIntExtra.Builder()
                                                .setValue(screen).build())
                                .build())
                        .build())
                .build();
    }

    private static float clamp(float v) { return v < 0.01f ? 0.01f : v; }

    private static DimensionBuilders.ContainerDimension weight(float w) {
        return new DimensionBuilders.ExpandedDimensionProp.Builder()
                .setLayoutWeight(new androidx.wear.protolayout.TypeBuilders.FloatProp.Builder(w).build())
                .build();
    }

    private LayoutElement hSpacer(float w) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setWidth(weightSpacer(w)).setHeight(DimensionBuilders.expand()).build();
    }
    private LayoutElement vSpacer(float w) {
        return new LayoutElementBuilders.Spacer.Builder()
                .setHeight(weightSpacer(w)).setWidth(DimensionBuilders.expand()).build();
    }
    private static DimensionBuilders.SpacerDimension weightSpacer(float w) {
        return new DimensionBuilders.ExpandedDimensionProp.Builder()
                .setLayoutWeight(new androidx.wear.protolayout.TypeBuilders.FloatProp.Builder(w).build())
                .build();
    }
}
