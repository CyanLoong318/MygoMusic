package com.allmusic.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索界面：搜索结果以“按钮行”展示，点击即点歌（更直观）。
 * 音源顺序：酷狗 / B站 / 网易云（默认酷狗）。
 * B站多分P视频：结果行标注分P数，点击后列出各分P（含标题），再点击即点歌。
 * 结果过多时分页：底部 [上一页] 第x/y页 [下一页]。
 *
 * 动态按钮模式：按钮槽位在 init() 建好固定数量（随屏幕高度），
 * render() 依据静态数据源 lastResults + 当前页刷新 文案/可见/可用。
 */
public class SearchScreen extends Screen {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-GUI");

    // 布局常量
    private static final int ROW_H = 22;          // 每行间距
    private static final int ROWS_TOP = 112;      // 结果区顶部 y
    private static final int BOTTOM_Y = 40;       // 底部控件(翻页/返回)预留高度
    private static final int BTN_W = 380;         // 结果行按钮宽度

    /** 单条搜索结果 */
    public static class SearchEntry {
        public final String source;
        public final String songId;
        public final String title;
        public final String artist;
        public final long duration;
        public final int pages; // B站分P数，其余为1

        public SearchEntry(String source, String songId, String title, String artist, long duration, int pages) {
            this.source = source;
            this.songId = songId;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
            this.pages = pages;
        }

        /** 去除可能的分P后缀，得到纯BV号 */
        public String getBv() {
            int h = songId.indexOf("#p");
            return h > 0 ? songId.substring(0, h) : songId;
        }

        public String getDurationText() {
            long seconds = duration / 1000;
            if (seconds <= 0) return "?";
            return String.format("%d:%02d", seconds / 60, seconds % 60);
        }
    }

    // 由网络线程写入，渲染线程读取
    public static volatile List<SearchEntry> lastResults = new ArrayList<>();
    public static volatile boolean searching = false;

    public static void onResults(List<SearchEntry> list) {
        lastResults = list != null ? list : new ArrayList<>();
        searching = false;
    }

    public static void onSearching() {
        lastResults = new ArrayList<>();
        searching = true;
    }

    private TextFieldWidget searchField;
    private final Screen parent;
    private String selectedSource = "kugou"; // 默认酷狗
    private ButtonWidget[] sourceBtns = new ButtonWidget[3];
    private final String[] sourceIds = {"kugou", "bilibili", "netease"};
    private final String[] sourceColors = {"§e", "§b", "§a"};
    private final String[] sourceNames = {"酷狗", "B站", "网易云"};

    // 结果按钮槽位（固定数量，内容在 render 刷新）
    private final List<ButtonWidget> rowButtons = new ArrayList<>();
    private int rowsVisible = 1;
    private int page = 0;
    private List<SearchEntry> boundResults = new ArrayList<>();
    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;

    public SearchScreen(Screen parent) {
        super(Text.literal("MygoMusic - 搜索歌曲"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        rowsVisible = Math.max(1, (height - BOTTOM_Y - ROWS_TOP) / ROW_H);
        page = 0;
        rowButtons.clear();

        // 音源按钮：酷狗 / B站 / 网易云
        int sourceY = 28;
        int bw = 76, gap = 6;
        int startX = centerX - ((bw * 3 + gap * 2) / 2);
        for (int i = 0; i < 3; i++) {
            final int fi = i;
            ButtonWidget btn = ButtonWidget.builder(Text.literal(""), b -> {
                selectedSource = sourceIds[fi];
                updateSourceButtons();
            }).dimensions(startX + i * (bw + gap), sourceY, bw, 18).build();
            sourceBtns[i] = btn;
            addDrawableChild(btn);
        }
        updateSourceButtons();

        // 搜索输入框
        searchField = new TextFieldWidget(textRenderer, centerX - 100, 54, 200, 20, Text.literal("输入歌名"));
        searchField.setPlaceholder(Text.literal("§7输入歌名搜索...（B站可输 BV号）"));
        searchField.setMaxLength(100);
        addDrawableChild(searchField);

        // 搜索按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("§6搜索"), button -> {
            search();
        }).dimensions(centerX - 50, 82, 100, 20).build());

        // 结果行槽位
        for (int s = 0; s < rowsVisible; s++) {
            final int slot = s;
            ButtonWidget btn = ButtonWidget.builder(Text.literal(""), b -> onClickRow(slot))
                    .dimensions(centerX - BTN_W / 2, ROWS_TOP + s * ROW_H, BTN_W, ROW_H - 2)
                    .build();
            rowButtons.add(btn);
            addDrawableChild(btn);
        }

        // 底部：返回（左下）
        addDrawableChild(ButtonWidget.builder(Text.literal("§7返回"), button -> {
            client.setScreen(parent);
        }).dimensions(12, height - BOTTOM_Y + 6, 70, 20).build());

        // 底部：翻页
        prevBtn = ButtonWidget.builder(Text.literal("§e上一页"), b -> {
            page = Math.max(0, page - 1);
        }).dimensions(centerX - 150, height - BOTTOM_Y + 6, 60, 20).build();
        addDrawableChild(prevBtn);

        nextBtn = ButtonWidget.builder(Text.literal("§e下一页"), b -> {
            page++;
        }).dimensions(centerX + 90, height - BOTTOM_Y + 6, 60, 20).build();
        addDrawableChild(nextBtn);
    }

    private void updateSourceButtons() {
        for (int i = 0; i < 3; i++) {
            boolean sel = selectedSource.equals(sourceIds[i]);
            String color = sel ? sourceColors[i] + "§l" : "§7";
            sourceBtns[i].setMessage(Text.literal(color + sourceNames[i]));
        }
    }

    /**
     * 执行搜索（留在当前界面，结果随后从服务端返回）
     */
    private void search() {
        String keyword = searchField.getText().trim();
        if (keyword.isEmpty()) return;
        onSearching();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand("mm search " + selectedSource + " " + keyword);
        }
    }

    /**
     * 点击某个结果行按钮（slot 为按钮下标，换算成整页内的真实序号）
     */
    private void onClickRow(int slot) {
        List<SearchEntry> rows = lastResults;
        int idx = page * rowsVisible + slot;
        if (idx < 0 || idx >= rows.size()) return;
        SearchEntry e = rows.get(idx);

        // B站多分P：先列出分P（含标题）供选择
        if ("bilibili".equals(e.source) && e.pages > 1) {
            onSearching();
            sendCommand("mm searchparts " + e.getBv());
            return;
        }
        // 普通结果：点击即点歌（/mm select 用服务端缓存的搜索结果）
        sendCommand("mm select " + (idx + 1));
    }

    private void sendCommand(String command) {
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }

    /**
     * 结果行的完整文案（含跨页连续的全局序号）
     */
    private String rowLabel(SearchEntry e, int number) {
        String line = "§e#" + number + " §f" + e.title + " §7- §f" + e.artist
                + " §7(" + sourceNames[indexOfSource(e.source)] + ") §7[" + e.getDurationText() + "]";
        if ("bilibili".equals(e.source) && e.pages > 1) {
            line += " §d§l[" + e.pages + "分P·点击选]";
        }
        return line;
    }

    private int indexOfSource(String source) {
        for (int i = 0; i < sourceIds.length; i++) if (sourceIds[i].equals(source)) return i;
        return 0;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        List<SearchEntry> display = lastResults;
        boolean isLoading = searching;

        // 结果列表引用变化（新一次搜索/分P返回）→ 回到第 1 页
        if (display != boundResults) {
            boundResults = display;
            page = 0;
        }

        int total = display.size();
        int totalPages = Math.max(1, (total + rowsVisible - 1) / rowsVisible);
        if (page >= totalPages) page = totalPages - 1;

        boolean showRows = !isLoading && total > 0;

        // 刷新结果行按钮（先于 super.render，保证按钮以最新内容绘制）
        for (int s = 0; s < rowButtons.size(); s++) {
            ButtonWidget b = rowButtons.get(s);
            int idx = page * rowsVisible + s;
            boolean inRange = showRows && idx < total;
            b.visible = inRange;
            b.active = inRange;
            if (inRange) {
                SearchEntry e = display.get(idx);
                b.setMessage(Text.literal(fitWidth(rowLabel(e, idx + 1), b.getWidth() - 10)));
            }
        }

        // 翻页控件可见/可用
        boolean showPage = showRows && totalPages > 1;
        prevBtn.visible = showPage;
        nextBtn.visible = showPage;
        if (showPage) {
            prevBtn.active = page > 0;
            nextBtn.active = page < totalPages - 1;
        }

        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);

        // 状态文案（搜索中 / 无结果提示）
        if (isLoading) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("§7搜索中..."), width / 2, ROWS_TOP + 10, 0xAAAAAA);
        } else if (total == 0) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("§8输入关键词后点「搜索」，结果显示在这里"), width / 2, ROWS_TOP + 10, 0x888888);
        }

        // 页码指示
        if (showPage) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("§7第 " + (page + 1) + "/" + totalPages + " 页"), width / 2, height - BOTTOM_Y + 12, 0xAAAAAA);
        }
    }

    /**
     * 截断过长的文本，防止超出按钮宽度
     */
    private String fitWidth(String text, int maxWidth) {
        if (maxWidth <= 0) return text;
        if (textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        return textRenderer.trimToWidth(text, Math.max(10, maxWidth - 4)) + "…";
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257) { // Enter
            search();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
