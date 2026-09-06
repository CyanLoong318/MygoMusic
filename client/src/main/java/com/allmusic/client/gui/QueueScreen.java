package com.allmusic.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 队列界面 - 显示整个播放列表：正在播放 / 等待队列 / 最近播放(已播放过的歌)
 */
public class QueueScreen extends Screen {
    private static final int LINE_HEIGHT = 12;
    private static final int CONTENT_LEFT = 12;
    private static final int CONTENT_RIGHT_MARGIN = 12;
    private static final int CONTENT_TOP = 60;
    private static final int CONTENT_BOTTOM_OFFSET = 46;

    private final Screen parent;
    private int scrollOffset = 0;

    public QueueScreen(Screen parent) {
        super(Text.literal("MygoMusic - 播放列表"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 刷新按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("刷新"), button -> {
            refresh();
        }).dimensions(width / 2 - 110, height - 30, 100, 20).build());

        // 返回按钮
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), button -> {
            client.setScreen(parent);
        }).dimensions(width / 2 + 10, height - 30, 100, 20).build());

        // 打开界面时主动向服务端请求一次最新队列
        refresh();
    }

    /**
     * 刷新队列 (向服务端请求最新队列状态)
     */
    private void refresh() {
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand("mm queue");
        }
    }

    /**
     * 内容区可显示的行数
     */
    private int maxRows() {
        int contentBottom = height - CONTENT_BOTTOM_OFFSET;
        int rows = (contentBottom - CONTENT_TOP) / LINE_HEIGHT;
        return Math.max(1, rows);
    }

    /**
     * 构造要显示的所有行（含区块标题），顺序：正在播放 -> 等待队列 -> 最近播放
     */
    private List<String> buildLines(QueueState state) {
        List<String> lines = new ArrayList<>();
        if (state == null) state = QueueState.EMPTY;

        QueueState.Song now = state.getNowPlaying();
        List<QueueState.Song> queue = state.getQueue();
        List<QueueState.Song> history = state.getHistory();

        // ---- 正在播放 ----
        if (now != null) {
            lines.add("§6§l正在播放");
            String prefix = state.isPlaying() ? "§e● " : "§7○ ";
            lines.add(songText(prefix, now, state.isPlaying() ? "§f" : "§7"));
        }

        // ---- 等待队列 ----
        lines.add("§a§l等待队列 §7(" + queue.size() + " 首)");
        if (queue.isEmpty()) {
            lines.add("§8- 暂无待播放歌曲 -");
        } else {
            for (int i = 0; i < queue.size(); i++) {
                QueueState.Song song = queue.get(i);
                lines.add(songText("§7#" + (i + 1) + " ", song, "§f"));
            }
        }

        // ---- 最近播放（已播放过的歌） ----
        lines.add("§7§l最近播放 §8(" + history.size() + " 首)");
        if (history.isEmpty()) {
            lines.add("§8- 还没有播放过的歌曲 -");
        } else {
            // 最近播放的在最前
            for (QueueState.Song song : history) {
                lines.add(songText("", song, "§7"));
            }
        }

        return lines;
    }

    private String songText(String prefix, QueueState.Song song, String bodyColor) {
        return prefix + bodyColor + song.title()
                + " §7- " + bodyColor + song.artist()
                + " §7(" + song.sourceDisplayName() + ") §8[点歌: " + song.requesterName() + "]";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        QueueState state = QueueState.get();
        int totalLines = buildLines(state).size();
        int maxScroll = Math.max(0, totalLines - maxRows());

        if (verticalAmount > 0) {
            scrollOffset--;
        } else if (verticalAmount < 0) {
            scrollOffset++;
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        // 标题
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFF);

        QueueState state = QueueState.get();
        if (state == null) state = QueueState.EMPTY;

        int maxWidth = width - CONTENT_LEFT - CONTENT_RIGHT_MARGIN;
        List<String> lines = buildLines(state);

        // 越界修正
        int maxScroll = Math.max(0, lines.size() - maxRows());
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        int contentBottom = height - CONTENT_BOTTOM_OFFSET;
        int rows = maxRows();

        for (int i = 0; i < rows; i++) {
            int idx = scrollOffset + i;
            if (idx >= lines.size()) break;
            int y = CONTENT_TOP + i * LINE_HEIGHT;
            if (y > contentBottom - LINE_HEIGHT) break;

            String line = lines.get(idx);
            context.drawTextWithShadow(textRenderer, Text.of(fitWidth(line, maxWidth)),
                    CONTENT_LEFT, y, 0xFFFFFF);
        }

        // 可滚动提示 / 统计
        if (lines.size() > rows) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.of("§7(滚轮滚动)"), width / 2, height - 38, 0xAAAAAA);
        }
    }

    /**
     * 截断过长的文本，防止超出屏幕
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
}
