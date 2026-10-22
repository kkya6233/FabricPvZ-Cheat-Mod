package cn.enaium.fabricpvz.quickcheats;

import cn.enaium.fabricpvz.api.lawn.LawnEvents;
import cn.enaium.fabricpvz.portable.lawn.Board;
import cn.enaium.fabricpvz.portable.lawn.LawnApp;
import cn.enaium.fabricpvz.portable.lawn.SeedBank;
import cn.enaium.fabricpvz.portable.lawn.SeedPacket;
import cn.enaium.fabricpvz.portable.sexy.KeyCode;
import cn.enaium.fabricpvz.portable.sexy.WidgetManager;
import net.fabricmc.api.ModInitializer;

/**
 * PvZ 快捷作弊键。
 *
 * <p>F7 —— 切换“所有卡片无需冷却”（再按一次恢复）；F8 —— 获得 999 阳光；F9 —— 直接判定本关胜利。
 *
 * <p>实现方式：注册 {@link LawnEvents#TICK}（由 {@code Board.updateGame()} 每帧派发），在回调里
 * 读取 {@link WidgetManager#mKeyDown} 的按压状态并做边沿判定，因此每个按键“按下一次触发一次”，
 * 不依赖当前焦点控件，也不需要 mixin、不改动游戏本体。
 */
public final class QuickCheatsMod implements ModInitializer {

    /** 模组 id，需与 fabric.mod.json 中的 id 一致。 */
    public static final String MOD_ID = "pvz-quick-cheats";

    /** 按下一次 F8 获得的阳光数量。 */
    public static final int SUN_PER_PRESS = 999;

    /** 正式游戏中的场景值（{@code LawnApp.mGameScene == 3}，即 updateGame 会跑的状态）。 */
    private static final int GAME_SCENE_PLAYING = 3;

    /**
     * 胜利后关卡收尾动画的帧数。
     *
     * <p>原版本关流程会给 {@code mBoardFadeOutCounter} 赋 600（旗关 500），原版跳关作弊键会把它
     * 压到 200，这里沿用该值：收尾表现保留，但结算明显更快。
     */
    private static final int WIN_FADE_OUT_TICKS = 200;

    /** 最后一搏（mGameMode == 31）的最终阶段下标，取自 {@code Board.isLastStandFinalStage()}。 */
    private static final int LAST_STAND_FINAL_STAGE = 4;

    /** 上一帧 F7 是否处于按下状态，用于边沿判定。 */
    private boolean f7Down;

    /** F7 开关：true 表示“所有卡片无需冷却”正在生效，再按一次 F7 关闭。 */
    private boolean noCooldownEnabled;

    /** 上一帧 F8 是否处于按下状态，用于边沿判定。 */
    private boolean f8Down;

    /** 上一帧 F9 是否处于按下状态，用于边沿判定。 */
    private boolean f9Down;

    @Override
    public void onInitialize() {
        LawnEvents.TICK.register(this::onTick);
    }

    private void onTick(Board board, int tick) {
        try {
            if (board == null) {
                return;
            }
            LawnApp app = board.mApp;
            if (app == null) {
                return;
            }
            WidgetManager widgetManager = app.mWidgetManager;
            if (widgetManager == null) {
                return;
            }

            boolean f7 = isKeyDown(widgetManager, KeyCode.KEYCODE_F7);
            boolean f8 = isKeyDown(widgetManager, KeyCode.KEYCODE_F8);
            boolean f9 = isKeyDown(widgetManager, KeyCode.KEYCODE_F9);

            if (f7 && !this.f7Down) {
                this.noCooldownEnabled = !this.noCooldownEnabled;
            }
            if (f8 && !this.f8Down) {
                giveSun(board);
            }
            if (f9 && !this.f9Down) {
                winLevel(board, app);
            }

            this.f7Down = f7;
            this.f8Down = f8;
            this.f9Down = f9;

            // 放在按键处理之后：刚按下打开的这一帧就生效，刚关闭的这一帧起不再干预。
            if (this.noCooldownEnabled) {
                clearCardCooldowns(board, app);
            }
        } catch (Throwable ignored) {
            // 监听器抛出的异常会经事件派发链传回 Board.updateGame()，进而打断游戏主循环，
            // 所以这里兜住：作弊键失败不影响正常游戏。
        }
    }

    /** 读取按键当前是否处于按下状态，下标越界时按未按下处理。 */
    private static boolean isKeyDown(WidgetManager widgetManager, int keyCode) {
        boolean[] keyDown = widgetManager.mKeyDown;
        return keyCode >= 0 && keyCode < keyDown.length && keyDown[keyCode];
    }

    /** F8：阳光 +999（走原版结算入口，会触发阳光收集事件与成就判定，上限 9990）。 */
    private static void giveSun(Board board) {
        board.addSunMoney(SUN_PER_PRESS);
    }

    /** F9：直接判定本关胜利并进入收尾流程。 */
    private static void winLevel(Board board, LawnApp app) {
        // 只在实际游戏中生效（原版作弊键同样要求 mGameScene != 2），避免在选卡/过场界面里
        // 改动关卡状态。
        if (app.mGameScene != GAME_SCENE_PLAYING) {
            return;
        }
        // 关卡已经在收尾（或已经判定结束、正在发放关卡奖励）时不再重复触发。
        if (board.mLevelComplete || board.mBoardFadeOutCounter >= 0 || board.mNextSurvivalStageCounter > 0) {
            return;
        }

        // 1 = 本关胜利（自然通关走的也是这个值）：存档会被正常清除，冒险模式进入奖励结算。
        app.mBoardResult = 1;

        // 生存 / 无尽：先跳到最终阶段，再收尾，否则结算只会过一小关。
        // 这里的 +5 沿用原版 '#' 作弊键的跳法：普通生存 stage>=4、困难/无尽 stage>=4 即为最终阶段。
        if (board.isSurvivalStageWithRepick()) {
            board.mCurrentWave = board.mNumWaves;
            board.mChallenge.mSurvivalStage += 5;
        }
        // 最后一搏（mGameMode == 31）：同样推进到最终阶段。
        if (board.isLastStandStageWithRepick()) {
            board.mCurrentWave = board.mNumWaves;
            board.mChallenge.mSurvivalStage = LAST_STAND_FINAL_STAGE;
        }

        board.removeAllZombies();
        board.fadeOutLevel();

        // fadeOutLevel() 在普通关卡里会把收尾帧数设为 600/500，这里压缩到 200 让结算更快；
        // 若当前仍处于“分阶段”流程（fadeOutLevel 走的是 mNextSurvivalStageCounter 分支），
        // 收尾计数不会被赋值，这个判断自然不生效。
        if (board.mBoardFadeOutCounter > WIN_FADE_OUT_TICKS) {
            board.mBoardFadeOutCounter = WIN_FADE_OUT_TICKS;
        }
    }

    /**
     * F7 开关打开时，把“正在冷却”的卡片恢复为可用状态。
     *
     * <p>游戏的冷却由两个字段表达：卡片被拾起时 {@code SeedPacket.mouseDown} 会调用
     * {@code deactivate()}（mActive = false），种下后 {@code wasPlanted()} 把 mRefreshing 置为 true
     * 并设好 mRefreshTime，之后由 {@code SeedPacket.update()} 每帧累加 mRefreshCounter，超过
     * mRefreshTime 才重新 {@code activate()}；而卡片能否被拾取，由 {@code SeedPacket.canPickUp()}
     * 里的 {@code !mActive} 判定。所以“无冷却”必须同时做到 mRefreshing = false 且 mActive = true。
     *
     * <p>只处理正在冷却的卡片，其余状态一律不碰：玩家拿在手上（mActive = false 且没在冷却）的卡片保持
     * 原样，取消种植时照常 activate()；空卡位（mPacketType == -1）跳过；传送带、老虎机这类本来就没有
     * 冷却的模式不会进入 mRefreshing，因此天然不受影响。关掉开关后立即停止干预，之后种下的卡片按原版
     * 规则重新冷却，所以开关可以来回反复切换。
     */
    private static void clearCardCooldowns(Board board, LawnApp app) {
        if (app.mGameScene != GAME_SCENE_PLAYING) {
            return;
        }
        SeedBank seedBank = board.mSeedBank;
        if (seedBank == null) {
            return;
        }
        SeedPacket[] packets = seedBank.mSeedPackets;
        if (packets == null) {
            return;
        }
        for (int i = 0; i < packets.length; ++i) {
            SeedPacket packet = packets[i];
            if (packet == null || packet.mPacketType == -1 || !packet.mRefreshing) {
                continue;
            }
            // 只置位，不调用 activate()：activate() 会顺带推进新手教程状态，在这里调用可能把教程步骤改乱。
            packet.mRefreshing = false;
            packet.mRefreshCounter = 0;
            packet.mActive = true;
        }
    }
}
