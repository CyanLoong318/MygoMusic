package com.allmusic.client.gui;

import com.allmusic.client.network.ChannelHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 队列界面 - 顶部显示正在播放，主体用“按钮行”展示等待队列 / 最近播放，可翻页。
 * 队列模式：行前缀 #全局序号（与 /mm remove 一致，#1 即下一首，正在播放不算在内）；
 *           点击歌曲 = 移出队列（仅本人所点，管理员可移任意）。
 * 历史模式：行前缀 #序号（最近播放优先）；点击歌曲 = 重新点歌（加入队尾）。
 *
 * 动态按钮模式：按钮槽位在 init() 建好固定数量（随屏幕高度），
 * render() 依据 QueueState.get() + 当前页刷新 文案/可见/可用。
 */
public class QueueScreen extends Screen {
    // 布局常量
    private static final int ROW_H = 22;          // 每行间距
    private static final int ROWS_TOP = 76;       // 结果区顶部 y
    private static final int BOTTOM_Y = 40;       // 底部控件(翻页/返回/刷新)预留高度

    private final Screen parent;
    private boolean historyMode = false;          // false=等待队列  true=最近播放
    private int page = 0;

    // 按钮槽位
    private final List<ButtonWidget> rowButtons = new ArrayList<>();
    private int rowsVisible = 1;
    private ButtonWidget queueModeBtn;
    private ButtonWidget historyModeBtn;
    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;

    public QueueScreen(Screen parent) {
        super(Text.literal("MygoMusic - 播放列表"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        rowsVisible = Math.max(1, (height - BOTTOM_Y - ROWS_TOP) / ROW_H);
        page = 0;
        rowButtons.clear();

        // 模式切换按钮：播放队列 / 最近播放
        queueModeBtn = ButtonWidget.builder(Text.literal(""), b -> {
            historyMode = false;
            page = 0;
        }).dimensions(centerX - 100, 44, 98, 20).build();
        addDrawableChild(queueModeBtn);

        historyModeBtn = ButtonWidget.builder(Text.literal(""), b -> {
            historyMode = true;
            page = 0;
        }).dimensions(centerX + 2, 44, 98, 20).build();
        addDrawableChild(historyModeBtn);

        // 结果行槽位（占满宽度）
        int rowW = Math.max(80, width - 24);
        for (int s = 0; s < rowsVisible; s++) {
            final int slot = s;
            ButtonWidget btn = ButtonWidget.builder(Text.literal(""), b -> onClickRow(slot))
                    .dimensions(12, ROWS_TOP + s * ROW_H, rowW, ROW_H - 2)
                    .build();
            rowButtons.add(btn);
            addDrawableChild(btn);
        }

        // 底部：返回（左下）、刷新（右下）
        addDrawableChild(ButtonWidget.builder(Text.literal("§7返回"), b -> {
            client.setScreen(parent);
        }).dimensions(6, height - 28, 60, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("§a刷新"), b -> {
            ChannelHandler.requestQueueSync();
        }).dimensions(width - 66, height - 28, 60, 20).build());

        // 底部：翻页
        prevBtn = ButtonWidget.builder(Text.literal("§e上一页"), b -> {
            page = Math.max(0, page - 1);
        }).dimensions(centerX - 125, height - 28, 60, 20).build();
        addDrawableChild(prevBtn);

        nextBtn = ButtonWidget.builder(Text.literal("§e下一页"), b -> {
            page++;
        }).dimensions(centerX + 65, height - 28, 60, 20).build();
        addDrawableChild(nextBtn);

        // 打开界面时主动向服务端请求一次最新队列状态（服务端回 QUEUE_SYNC 更新快照）
        ChannelHandler.requestQueueSync();
    }

    private void sendCommand(String command) {
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }

    /**
     * 点击某个结果行按钮
     */
    private void onClickRow(int slot) {
        QueueState state = QueueState.get();
        if (state == null) state = QueueState.EMPTY;
        List<QueueState.Song> list = historyMode ? state.getHistory() : state.getQueue();
        int idx = page * rowsVisible + slot;
        if (idx < 0 || idx >= list.size()) return;
        QueueState.Song song = list.get(idx);

        if (historyMode) {
            // 历史 → 重新点歌（加入队尾）
            sendCommand("mm playid " + song.source() + " " + song.songId());
        } else {
            // 队列 → 移出队列（仅本人所点；管理员可移任意）
            sendCommand("mm remove " + (idx + 1));
        }
    }

    /**
     * 当前行的数据源列表（等待队列 or 最近播放）
     */
    private List<QueueState.Song> currentList(QueueState state) {
        return historyMode ? state.getHistory() : state.getQueue();
    }

    private String requesterName(QueueState.Song song) {
        String r = song.requesterName();
        return (r == null || r.isEmpty()) ? "-" : r;
    }

    private String rowLabel(QueueState.Song song, int number) {
        String prefix = historyMode ? "§7#" : "§e#";
        return prefix + number + " §f" + song.title()
                + " §7- §f" + song.artist()
                + " §7(" + song.sourceDisplayName() + ") §8[点歌: " + requesterName(song) + "]";
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        QueueState state = QueueState.get();
        if (state == null) state = QueueState.EMPTY;

        QueueState.Song now = state.getNowPlaying();
        List<QueueState.Song> list = currentList(state);
        int queueCount = state.getQueue().size();
        int historyCount = state.getHistory().size();

        // 模式切换按钮：计数 + 选中高亮
        queueModeBtn.setMessage(Text.literal(historyMode ? "§7播放队列(" + queueCount + ")" : "§a§l播放队列(" + queueCount + ")"));
        historyModeBtn.setMessage(Text.literal(historyMode ? "§e§l最近播放(" + historyCount + ")" : "§7最近播放(" + historyCount + ")"));

        // 页码计算（列表变化/切模式后越界修正）
        int total = list.size();
        int totalPages = Math.max(1, (total + rowsVisible - 1) / rowsVisible);
        if (page >= totalPages) page = totalPages - 1;

        boolean showRows = total > 0;

        // 刷新结果行按钮（先于 super.render，保证按钮以最新内容绘制）
        for (int s = 0; s < rowButtons.size(); s++) {
            ButtonWidget b = rowButtons.get(s);
            int idx = page * rowsVisible + s;
            boolean inRange = showRows && idx < total;
            b.visible = inRange;
            b.active = inRange;
            if (inRange) {
                QueueState.Song song = list.get(idx);
                b.setMessage(Text.literal(fitWidth(rowLabel(song, idx + 1), b.getWidth() - 10)));
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

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);

        // 正在播放信息行
        String np;
        if (now == null) {
            np = "§7当前没有正在播放";
        } else {
            np = "§6正在播放 §f" + now.title() + " §7- §f" + now.artist()
                    + " §7(" + now.sourceDisplayName() + ") §8[点歌: " + requesterName(now) + "]";
            if (state.isPaused()) {
                np += "  §c[已暂停]";
            } else if (state.isPlaying()) {
                np += "  §a[播放中]";
            }
        }
        context.drawTextWithShadow(textRenderer, Text.literal(fitWidth(np, width - 24)), 12, 28, 0xFFFFFF);

        // 模式操作提示（第一行下沿小字）
        String hint = historyMode
                ? "§7点击歌曲 = 重新点歌（加入队尾）"
                : "§7点击歌曲 = 移出队列（仅本人所点，管理员可移任意）";
        context.drawTextWithShadow(textRenderer, Text.literal(hint), 12, ROWS_TOP - 10, 0x777777);

        // 空数据提示
        if (total == 0) {
            String empty = historyMode ? "§8- 还没有播放过的歌曲 -" : "§8- 等待队列暂无歌曲，去搜索点歌吧 -";
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(empty), width / 2, ROWS_TOP + 10, 0x888888);
        }

        // 页码指示
        if (showPage) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("§7第 " + (page + 1) + "/" + totalPages + " 页"), width / 2, height - 22, 0xAAAAAA);
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
}
