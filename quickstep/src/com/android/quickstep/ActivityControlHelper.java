/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep;

import static android.view.View.TRANSLATION_Y;
import static com.android.launcher3.LauncherAnimUtils.OVERVIEW_TRANSITION_MS;
import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.LauncherState.BACKGROUND_APP;
import static com.android.launcher3.LauncherState.FAST_OVERVIEW;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.allapps.AllAppsTransitionController.ALL_APPS_PROGRESS;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.launcher3.states.RotationHelper.REQUEST_LOCK;
import static com.android.quickstep.TouchConsumer.INTERACTION_NORMAL;
import static com.android.quickstep.TouchConsumer.INTERACTION_QUICK_SCRUB;
import static com.android.quickstep.views.RecentsView.CONTENT_ALPHA;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_BACK;
import static com.android.systemui.shared.system.NavigationBarCompat.HIT_TARGET_ROTATION;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.TargetApi;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherInitListener;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.TestProtocol;
import com.android.launcher3.allapps.AllAppsTransitionController;
import com.android.launcher3.allapps.DiscoveryBounce;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.compat.AccessibilityManagerCompat;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.uioverrides.FastOverviewState;
import com.android.launcher3.userevent.nano.LauncherLogProto;
import com.android.launcher3.util.MultiValueAlpha.AlphaProperty;
import com.android.quickstep.TouchConsumer.InteractionType;
import com.android.quickstep.util.ClipAnimationHelper;
import com.android.quickstep.util.LayoutUtils;
import com.android.quickstep.util.RemoteAnimationProvider;
import com.android.quickstep.util.RemoteAnimationTargetSet;
import com.android.quickstep.util.TransformedRect;
import com.android.quickstep.views.LauncherLayoutListener;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Utility class which abstracts out the logical differences between Launcher and RecentsActivity.
 */
@TargetApi(Build.VERSION_CODES.P)
public interface ActivityControlHelper<T extends BaseDraggingActivity> {

    LayoutListener createLayoutListener(T activity);

    /**
     * Updates the UI to indicate quick interaction.
     */
    void onQuickInteractionStart(T activity, @Nullable RunningTaskInfo taskInfo,
            boolean activityVisible, TouchInteractionLog touchInteractionLog);

    float getTranslationYForQuickScrub(TransformedRect targetRect, DeviceProfile dp,
            Context context);

    void executeOnWindowAvailable(T activity, Runnable action);

    void onTransitionCancelled(T activity, boolean activityVisible);

    int getSwipeUpDestinationAndLength(DeviceProfile dp, Context context,
            @InteractionType int interactionType, TransformedRect outRect);

    void onSwipeUpComplete(T activity);

    AnimationFactory prepareRecentsUI(T activity, boolean activityVisible,
            boolean animateActivity, Consumer<AnimatorPlaybackController> callback);

    ActivityInitListener createActivityInitListener(BiPredicate<T, Boolean> onInitListener);

    @Nullable
    T getCreatedActivity();

    @UiThread
    @Nullable
    RecentsView getVisibleRecentsView();

    @UiThread
    boolean switchToRecentsIfVisible(boolean fromRecentsButton);

    Rect getOverviewWindowBounds(Rect homeBounds, RemoteAnimationTargetCompat target);

    boolean shouldMinimizeSplitScreen();

    /**
     * @return {@code true} if recents activity should be started immediately on touchDown,
     *         {@code false} if it should deferred until some threshold is crossed.
     */
    boolean deferStartingActivity(int downHitTarget);

    boolean supportsLongSwipe(T activity);

    AlphaProperty getAlphaProperty(T activity);

    /**
     * Must return a non-null controller is supportsLongSwipe was true.
     */
    LongSwipeHelper getLongSwipeController(T activity, int runningTaskId);

    /**
     * Used for containerType in {@link com.android.launcher3.logging.UserEventDispatcher}
     */
    int getContainerType();

    class LauncherActivityControllerHelper implements ActivityControlHelper<Launcher> {

        @Override
        public LayoutListener createLayoutListener(Launcher activity) {
            return new LauncherLayoutListener(activity);
        }

        @Override
        public void onQuickInteractionStart(Launcher activity, RunningTaskInfo taskInfo,
                boolean activityVisible, TouchInteractionLog touchInteractionLog) {
            LauncherState fromState = activity.getStateManager().getState();
            QuickScrubController controller = activity.<RecentsView>getOverviewPanel()
                    .getQuickScrubController();
            boolean isQuickSwitch = controller.isQuickSwitch();
            boolean animate = activityVisible;
            if (isQuickSwitch && fromState == FAST_OVERVIEW && !animate) {
                // We can already be in FAST_OVERVIEW if createActivityController() was called
                // before us. This could happen, for instance, when launcher is slow to load when
                // starting quick switch, causing us to call onQuickScrubStart() on the background
                // thread. In this case, we also hadn't set isQuickSwitch = true before setting
                // FAST_OVERVIEW, so we need to reapply FAST_OVERVIEW to take that into account.
                activity.getStateManager().reapplyState();
            } else {
                activity.getStateManager().goToState(FAST_OVERVIEW, animate);
            }

            controller.onQuickScrubStart(activityVisible && !fromState.overviewUi, this,
                    touchInteractionLog);

            if (!activityVisible) {
                // For the duration of the gesture, lock the screen orientation to ensure that we
                // do not rotate mid-quickscrub
                activity.getRotationHelper().setStateHandlerRequest(REQUEST_LOCK);
            }
        }

        @Override
        public float getTranslationYForQuickScrub(TransformedRect targetRect, DeviceProfile dp,
                Context context) {
            // The padding calculations are exactly same as that of RecentsView.setInsets
            int topMargin = context.getResources()
                    .getDimensionPixelSize(R.dimen.task_thumbnail_top_margin);
            int paddingTop = targetRect.rect.top - topMargin - dp.getInsets().top;
            int paddingBottom = dp.availableHeightPx + dp.getInsets().top - targetRect.rect.bottom;

            return FastOverviewState.OVERVIEW_TRANSLATION_FACTOR * (paddingBottom - paddingTop);
        }

        @Override
        public void executeOnWindowAvailable(Launcher activity, Runnable action) {
            activity.getWorkspace().runOnOverlayHidden(action);
        }

        @Override
        public int getSwipeUpDestinationAndLength(DeviceProfile dp, Context context,
                @InteractionType int interactionType, TransformedRect outRect) {
            LayoutUtils.calculateLauncherTaskSize(context, dp, outRect.rect);
            if (interactionType == INTERACTION_QUICK_SCRUB) {
                outRect.scale = FastOverviewState.getOverviewScale(dp, outRect.rect, context,
                        FeatureFlags.QUICK_SWITCH.get());
            }
            if (dp.isVerticalBarLayout()) {
                Rect targetInsets = dp.getInsets();
                int hotseatInset = dp.isSeascape() ? targetInsets.left : targetInsets.right;
                return dp.hotseatBarSizePx + hotseatInset;
            } else {
                return LayoutUtils.getShelfTrackingDistance(dp);
            }
        }

        @Override
        public void onTransitionCancelled(Launcher activity, boolean activityVisible) {
            LauncherState startState = activity.getStateManager().getRestState();
            activity.getStateManager().goToState(startState, activityVisible);
        }

        @Override
        public void onSwipeUpComplete(Launcher activity) {
            // Re apply state in case we did something funky during the transition.
            activity.getStateManager().reapplyState();
            DiscoveryBounce.showForOverviewIfNeeded(activity);
        }

        @Override
        public AnimationFactory prepareRecentsUI(Launcher activity, boolean activityVisible,
                boolean animateActivity, Consumer<AnimatorPlaybackController> callback) {
            final LauncherState startState = activity.getStateManager().getState();

            LauncherState resetState = startState;
            if (startState.disableRestore) {
                resetState = activity.getStateManager().getRestState();
            }
            activity.getStateManager().setRestState(resetState);

            final LauncherState fromState;
            if (!activityVisible) {
                // Since the launcher is not visible, we can safely reset the scroll position.
                // This ensures then the next swipe up to all-apps starts from scroll 0.
                activity.getAppsView().reset(false /* animate */);
                fromState = animateActivity ? BACKGROUND_APP : OVERVIEW;
                activity.getStateManager().goToState(fromState, false);

                // Optimization, hide the all apps view to prevent layout while initializing
                activity.getAppsView().getContentView().setVisibility(View.GONE);

                AccessibilityManagerCompat.sendEventToTest(
                        activity, TestProtocol.SWITCHED_TO_STATE_MESSAGE);
            } else {
                fromState = startState;
            }

            return new AnimationFactory() {
                @Override
                public void createActivityController(long transitionLength,
                        @InteractionType int interactionType) {
                    createActivityControllerInternal(activity, activityVisible, fromState,
                            transitionLength, interactionType, callback);
                }

                @Override
                public void onTransitionCancelled() {
                    activity.getStateManager().goToState(startState, false /* animate */);
                }
            };
        }

        private void createActivityControllerInternal(Launcher activity, boolean wasVisible,
                LauncherState fromState, long transitionLength,
                @InteractionType int interactionType,
                Consumer<AnimatorPlaybackController> callback) {
            LauncherState endState = interactionType == INTERACTION_QUICK_SCRUB
                    ? FAST_OVERVIEW : OVERVIEW;
            if (wasVisible) {
                DeviceProfile dp = activity.getDeviceProfile();
                long accuracy = 2 * Math.max(dp.widthPx, dp.heightPx);
                callback.accept(activity.getStateManager()
                        .createAnimationToNewWorkspace(fromState, endState, accuracy));
                return;
            }
            if (fromState == endState) {
                return;
            }

            AnimatorSet anim = new AnimatorSet();
            if (!activity.getDeviceProfile().isVerticalBarLayout()) {
                AllAppsTransitionController controller = activity.getAllAppsController();
                ObjectAnimator shiftAnim = ObjectAnimator.ofFloat(controller, ALL_APPS_PROGRESS,
                        fromState.getVerticalProgress(activity),
                        endState.getVerticalProgress(activity));
                shiftAnim.setInterpolator(LINEAR);
                anim.play(shiftAnim);
            }

            if (interactionType == INTERACTION_NORMAL) {
                playScaleDownAnim(anim, activity, endState);
            }

            anim.setDuration(transitionLength * 2);
            activity.getStateManager().setCurrentAnimation(anim);
            AnimatorPlaybackController controller =
                    AnimatorPlaybackController.wrap(anim, transitionLength * 2);

            // Since we are changing the start position of the UI, reapply the state, at the end
            controller.setEndAction(() ->
                activity.getStateManager().goToState(
                        controller.getProgressFraction() > 0.5 ? endState : fromState, false));
            callback.accept(controller);
        }

        /**
         * Scale down recents from the center task being full screen to being in overview.
         */
        private void playScaleDownAnim(AnimatorSet anim, Launcher launcher,
                LauncherState endState) {
            RecentsView recentsView = launcher.getOverviewPanel();
            TaskView v = recentsView.getTaskViewAt(recentsView.getCurrentPage());
            if (v == null) {
                return;
            }

            // Setup the clip animation helper source/target rects in the final transformed state
            // of the recents view (a scale may be applied prior to this animation starting to
            // line up the side pages during swipe up)
            float prevRvScale = recentsView.getScaleX();
            float targetRvScale = endState.getOverviewScaleAndTranslationYFactor(launcher)[0];
            SCALE_PROPERTY.set(recentsView, targetRvScale);
            ClipAnimationHelper clipHelper = new ClipAnimationHelper();
            clipHelper.fromTaskThumbnailView(v.getThumbnail(), (RecentsView) v.getParent(), null);
            SCALE_PROPERTY.set(recentsView, prevRvScale);

            if (!clipHelper.getSourceRect().isEmpty() && !clipHelper.getTargetRect().isEmpty()) {
                float fromScale = clipHelper.getSourceRect().width()
                        / clipHelper.getTargetRect().width();
                float fromTranslationY = clipHelper.getSourceRect().centerY()
                        - clipHelper.getTargetRect().centerY();
                Animator scale = ObjectAnimator.ofFloat(recentsView, SCALE_PROPERTY, fromScale, 1);
                Animator translateY = ObjectAnimator.ofFloat(recentsView, TRANSLATION_Y,
                        fromTranslationY, 0);
                scale.setInterpolator(LINEAR);
                translateY.setInterpolator(LINEAR);
                anim.playTogether(scale, translateY);
            }
        }

        @Override
        public ActivityInitListener createActivityInitListener(
                BiPredicate<Launcher, Boolean> onInitListener) {
            return new LauncherInitListener(onInitListener);
        }

        @Nullable
        @Override
        public Launcher getCreatedActivity() {
            LauncherAppState app = LauncherAppState.getInstanceNoCreate();
            if (app == null) {
                return null;
            }
            return (Launcher) app.getModel().getCallback();
        }

        @Nullable
        @UiThread
        private Launcher getVisibleLaucher() {
            Launcher launcher = getCreatedActivity();
            return (launcher != null) && launcher.isStarted() && launcher.hasWindowFocus() ?
                    launcher : null;
        }

        @Nullable
        @Override
        public RecentsView getVisibleRecentsView() {
            Launcher launcher = getVisibleLaucher();
            return launcher != null && launcher.getStateManager().getState().overviewUi
                    ? launcher.getOverviewPanel() : null;
        }

        @Override
        public boolean switchToRecentsIfVisible(boolean fromRecentsButton) {
            Launcher launcher = getVisibleLaucher();
            if (launcher != null) {
                if (fromRecentsButton) {
                    launcher.getUserEventDispatcher().logActionCommand(
                            LauncherLogProto.Action.Command.RECENTS_BUTTON,
                            getContainerType(),
                            LauncherLogProto.ContainerType.TASKSWITCHER);
                }
                launcher.getStateManager().goToState(OVERVIEW);
                return true;
            }
            return false;
        }

        @Override
        public boolean deferStartingActivity(int downHitTarget) {
            return downHitTarget == HIT_TARGET_BACK || downHitTarget == HIT_TARGET_ROTATION;
        }

        @Override
        public Rect getOverviewWindowBounds(Rect homeBounds, RemoteAnimationTargetCompat target) {
            return homeBounds;
        }

        @Override
        public boolean shouldMinimizeSplitScreen() {
            return true;
        }

        @Override
        public boolean supportsLongSwipe(Launcher activity) {
            return !activity.getDeviceProfile().isVerticalBarLayout();
        }

        @Override
        public LongSwipeHelper getLongSwipeController(Launcher activity, int runningTaskId) {
            if (activity.getDeviceProfile().isVerticalBarLayout()) {
                return null;
            }
            return new LongSwipeHelper(activity, runningTaskId);
        }

        @Override
        public AlphaProperty getAlphaProperty(Launcher activity) {
            return activity.getDragLayer().getAlphaProperty(DragLayer.ALPHA_INDEX_SWIPE_UP);
        }

        @Override
        public int getContainerType() {
            final Launcher launcher = getVisibleLaucher();
            return launcher != null ? launcher.getStateManager().getState().containerType
                    : LauncherLogProto.ContainerType.APP;
        }
    }

    class FallbackActivityControllerHelper implements ActivityControlHelper<RecentsActivity> {

        private final ComponentName mHomeComponent;
        private final Handler mUiHandler = new Handler(Looper.getMainLooper());

        public FallbackActivityControllerHelper(ComponentName homeComponent) {
            mHomeComponent = homeComponent;
        }

        @Override
        public void onQuickInteractionStart(RecentsActivity activity, RunningTaskInfo taskInfo,
                boolean activityVisible, TouchInteractionLog touchInteractionLog) {
            QuickScrubController controller = activity.<RecentsView>getOverviewPanel()
                    .getQuickScrubController();

            // TODO: match user is as well
            boolean startingFromHome = !activityVisible &&
                    (taskInfo == null || Objects.equals(taskInfo.topActivity, mHomeComponent));
            controller.onQuickScrubStart(startingFromHome, this, touchInteractionLog);
            if (activityVisible) {
                mUiHandler.postDelayed(controller::onFinishedTransitionToQuickScrub,
                        OVERVIEW_TRANSITION_MS);
            }
        }

        @Override
        public float getTranslationYForQuickScrub(TransformedRect targetRect, DeviceProfile dp,
                Context context) {
            return 0;
        }

        @Override
        public void executeOnWindowAvailable(RecentsActivity activity, Runnable action) {
            action.run();
        }

        @Override
        public void onTransitionCancelled(RecentsActivity activity, boolean activityVisible) {
            // TODO:
        }

        @Override
        public int getSwipeUpDestinationAndLength(DeviceProfile dp, Context context,
                @InteractionType int interactionType, TransformedRect outRect) {
            LayoutUtils.calculateFallbackTaskSize(context, dp, outRect.rect);
            if (dp.isVerticalBarLayout()) {
                Rect targetInsets = dp.getInsets();
                int hotseatInset = dp.isSeascape() ? targetInsets.left : targetInsets.right;
                return dp.hotseatBarSizePx + hotseatInset;
            } else {
                return dp.heightPx - outRect.rect.bottom;
            }
        }

        @Override
        public void onSwipeUpComplete(RecentsActivity activity) {
            // TODO:
        }

        @Override
        public AnimationFactory prepareRecentsUI(RecentsActivity activity, boolean activityVisible,
                boolean animateActivity, Consumer<AnimatorPlaybackController> callback) {
            if (activityVisible) {
                return (transitionLength, interactionType) -> { };
            }

            RecentsView rv = activity.getOverviewPanel();
            rv.setContentAlpha(0);

            return new AnimationFactory() {

                boolean isAnimatingHome = false;

                @Override
                public void onRemoteAnimationReceived(RemoteAnimationTargetSet targets) {
                    isAnimatingHome = targets != null && targets.isAnimatingHome();
                    if (!isAnimatingHome) {
                        rv.setContentAlpha(1);
                    }
                    createActivityController(getSwipeUpDestinationAndLength(
                            activity.getDeviceProfile(), activity, INTERACTION_NORMAL,
                            new TransformedRect()), INTERACTION_NORMAL);
                }

                @Override
                public void createActivityController(long transitionLength, int interactionType) {
                    if (!isAnimatingHome) {
                        return;
                    }

                    ObjectAnimator anim = ObjectAnimator.ofFloat(rv, CONTENT_ALPHA, 0, 1);
                    anim.setDuration(transitionLength).setInterpolator(LINEAR);
                    AnimatorSet animatorSet = new AnimatorSet();
                    animatorSet.play(anim);
                    callback.accept(AnimatorPlaybackController.wrap(animatorSet, transitionLength));
                }
            };
        }

        @Override
        public LayoutListener createLayoutListener(RecentsActivity activity) {
            // We do not change anything as part of layout changes in fallback activity. Return a
            // default layout listener.
            return new LayoutListener() {
                @Override
                public void open() { }

                @Override
                public void setHandler(WindowTransformSwipeHandler handler) { }

                @Override
                public void finish() { }

                @Override
                public void update(boolean shouldFinish, boolean isLongSwipe, RectF currentRect) { }
            };
        }

        @Override
        public ActivityInitListener createActivityInitListener(
                BiPredicate<RecentsActivity, Boolean> onInitListener) {
            return new RecentsActivityTracker(onInitListener);
        }

        @Nullable
        @Override
        public RecentsActivity getCreatedActivity() {
            return RecentsActivityTracker.getCurrentActivity();
        }

        @Nullable
        @Override
        public RecentsView getVisibleRecentsView() {
            RecentsActivity activity = getCreatedActivity();
            if (activity != null && activity.hasWindowFocus()) {
                return activity.getOverviewPanel();
            }
            return null;
        }

        @Override
        public boolean switchToRecentsIfVisible(boolean fromRecentsButton) {
            return false;
        }

        @Override
        public boolean deferStartingActivity(int downHitTarget) {
            // Always defer starting the activity when using fallback
            return true;
        }

        @Override
        public Rect getOverviewWindowBounds(Rect homeBounds, RemoteAnimationTargetCompat target) {
            // TODO: Remove this once b/77875376 is fixed
            return target.sourceContainerBounds;
        }

        @Override
        public boolean shouldMinimizeSplitScreen() {
            // TODO: Remove this once b/77875376 is fixed
            return false;
        }

        @Override
        public boolean supportsLongSwipe(RecentsActivity activity) {
            return false;
        }

        @Override
        public LongSwipeHelper getLongSwipeController(RecentsActivity activity, int runningTaskId) {
            return null;
        }

        @Override
        public AlphaProperty getAlphaProperty(RecentsActivity activity) {
            return activity.getDragLayer().getAlphaProperty(0);
        }

        @Override
        public int getContainerType() {
            return LauncherLogProto.ContainerType.SIDELOADED_LAUNCHER;
        }
    }

    interface LayoutListener {

        void open();

        void setHandler(WindowTransformSwipeHandler handler);

        void finish();

        void update(boolean shouldFinish, boolean isLongSwipe, RectF currentRect);
    }

    interface ActivityInitListener {

        void register();

        void unregister();

        void registerAndStartActivity(Intent intent, RemoteAnimationProvider animProvider,
                Context context, Handler handler, long duration);
    }

    interface AnimationFactory {

        default void onRemoteAnimationReceived(RemoteAnimationTargetSet targets) { }

        void createActivityController(long transitionLength, @InteractionType int interactionType);

        default void onTransitionCancelled() { }
    }
}
