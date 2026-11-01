package com.mrstudios.trades.hook;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Bedrock players and their forms, as the trade logic sees them, so that logic can be tested
 * without Floodgate. Form handlers may run on any thread; callers hop back to the main thread.
 */
public interface BedrockForms {

    boolean isBedrock(UUID player);

    /**
     * A two-button form. {@code onAnswer} gets true for the first button, false for the second;
     * closing the form calls nothing.
     *
     * @return false if the form couldn't be sent
     */
    boolean sendChoice(UUID player, String title, String content, String first, String second, Consumer<Boolean> onAnswer);

    /**
     * A form with one text field.
     *
     * @return false if the form couldn't be sent
     */
    boolean sendInput(UUID player, String title, String label, String current, Consumer<String> onAnswer,
            Runnable onClosed);

    /**
     * A form with one whole-number slider from 0 to {@code max}.
     *
     * @return false if the form couldn't be sent
     */
    boolean sendSlider(UUID player, String title, String label, int max, int current, IntConsumer onAnswer,
            Runnable onClosed);
}
