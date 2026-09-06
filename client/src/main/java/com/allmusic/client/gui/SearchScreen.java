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
 * 搜索界面：搜索结果直接显示在界面里，点击即点歌。
 * 音源顺序：酷狗 / B站 / 网易云（默认酷狗）。
 * B站多分P视频：结果行标注分P数，点击后列出各分P（含标题），再点击即点歌。
 */
public class SearchScreen extends Screen {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-GUI");

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

    private List<SearchEntry> displayResults = new ArrayList<>();
    private boolean showSearching = false;

    public SearchScreen(Screen parent) {
        super(Text.literal("MygoMusic - 搜索歌曲"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 音源按钮：酷狗 / B站 / 网易云
        int sourceY = 30;
        int bw = 76, gap = 6;
        int startX = width / 2 - ((bw * 3 + gap * 2) / 2);
        for (int i = 0; i < 3; i++) {
            final int fi = i;
            ButtonWidget btn = ButtonWidget.builder(Text.literal(""), b -> {
                selectedSource = sourceIds[fi];
                updateSourceButtons();
            }).dimensions(startX + i * (bw + gap), sourceY, bw, 20).build();
            sourceBtns[i] = btn;
            addDrawableChild(btn);
        }
        updateSourceButtons();

        // 搜索输入框
        searchField = new TextFieldWidget(textRenderer, width / 2 - 100, 60, 200, 20, Text.literal("输入歌名"));
        searchField.setPlaceholder(Text.literal("§7输入歌名搜索...（B站可输 BV号）"));
        searchField.setMaxLength(100);
        addDrawableChild(searchField);

        // 搜索按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("§6搜索"), button -> {
            search();
        }).dimensions(width / 2 - 50, 90, 100, 20).build());

        // 返回按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("§7返回"), button -> {
            client.setScreen(parent);
        }).dimensions(width / 2 - 50, height - 28, 100, 20).build());

        displayResults = lastResults;
        showSearching = searching;
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
        displayResults = new ArrayList<>();
        showSearching = true;
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand("mm search " + selectedSource + " " + keyword);
        }
    }

    private int resultTopY() {
        return 128;
    }
    private int resultRowH() {
        return 22;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);

        displayResults = lastResults;
        showSearching = searching;

        int y = resultTopY();
        if (showSearching) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("§7搜索中..."), width / 2, y, 0xAAAAAA);
            return;
        }
        if (displayResults.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("§8输入关键词后点「搜索」，结果显示在这里"), width / 2, y, 0x888888);
            return;
        }
        for (int i = 0; i < displayResults.size(); i++) {
            SearchEntry e = displayResults.get(i);
            int rowY = y + i * resultRowH();
            String line = "§e#" + (i + 1) + " §f" + e.title + " §7- §f" + e.artist
                    + " §7(" + sourceNames[indexOfSource(e.source)] + ") §7[" + e.getDurationText() + "]";
            if ("bilibili".equals(e.source) && e.pages > 1) {
                line += " §d§l[" + e.pages + "分P·点击选择]";
            }
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(line), width / 2, rowY, 0xFFFFFF);
        }
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7点击结果即点歌"), width / 2, y + displayResults.size() * resultRowH() + 6, 0x777777);
    }

    private int indexOfSource(String source) {
        for (int i = 0; i < sourceIds.length; i++) if (sourceIds[i].equals(source)) return i;
        return 0;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int y = resultTopY();
        List<SearchEntry> rows = lastResults;
        for (int i = 0; i < rows.size(); i++) {
            int rowY = y + i * resultRowH();
            if (mouseX >= width / 2 - 190 && mouseX <= width / 2 + 190
                    && mouseY >= rowY - 8 && mouseY <= rowY + 8) {
                SearchEntry e = rows.get(i);
                // B站多分P：先列出分P（含标题）供选择
                if ("bilibili".equals(e.source) && e.pages > 1) {
                    onSearching();
                    displayResults = new ArrayList<>();
                    showSearching = true;
                    sendCommand("mm searchparts " + e.getBv());
                    return true;
                }
                // 普通结果：点击即点歌（/mm select 用服务端缓存的搜索结果）
                sendCommand("mm select " + (i + 1));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void sendCommand(String command) {
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
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
