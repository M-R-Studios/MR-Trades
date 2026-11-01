package com.mrstudios.trades.hook;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.util.FormBuilder;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Bedrock detection and forms through Floodgate (DESIGN.md 4.4). The only class that touches
 * Floodgate or Cumulus; it's only loaded when Floodgate is enabled, so MR-Trades runs without it.
 */
final class FloodgateHook implements BedrockForms {

    private final FloodgateApi api;
    private final Logger log;

    private FloodgateHook(FloodgateApi api, Logger log) {
        this.api = api;
        this.log = log;
    }

    /** Only call when Floodgate is enabled. @return the hook, or null if its API isn't usable. */
    static BedrockForms create(Logger log) {
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            if (api == null) {
                log.warning("Floodgate is enabled but its API isn't available; Bedrock forms are off.");
                return null;
            }
            return new FloodgateHook(api, log);
        } catch (LinkageError | RuntimeException e) {
            log.warning("Floodgate hook disabled: " + e);
            return null;
        }
    }

    @Override
    public boolean isBedrock(UUID player) {
        try {
            return api.isFloodgatePlayer(player);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Floodgate check failed; treating the player as a Java player", e);
            return false;
        }
    }

    @Override
    public boolean sendChoice(UUID player, String title, String content, String first, String second,
            Consumer<Boolean> onAnswer) {
        return send(player, ModalForm.builder()
                .title(title)
                .content(content)
                .button1(first)
                .button2(second)
                .validResultHandler(response -> onAnswer.accept(response.clickedFirst())));
    }

    @Override
    public boolean sendInput(UUID player, String title, String label, String current, Consumer<String> onAnswer,
            Runnable onClosed) {
        return send(player, CustomForm.builder()
                .title(title)
                .input(label, "", current)
                .validResultHandler(response -> onAnswer.accept(response.asInput(0)))
                .closedOrInvalidResultHandler(onClosed));
    }

    @Override
    public boolean sendSlider(UUID player, String title, String label, int max, int current, IntConsumer onAnswer,
            Runnable onClosed) {
        return send(player, CustomForm.builder()
                .title(title)
                .slider(label, 0, Math.max(1, max), 1, Math.min(current, max))
                .validResultHandler(response -> onAnswer.accept(Math.round(response.asSlider(0))))
                .closedOrInvalidResultHandler(onClosed));
    }

    private boolean send(UUID player, FormBuilder<?, ?, ?> form) {
        try {
            return api.sendForm(player, form);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Couldn't send a Bedrock form", e);
            return false;
        }
    }
}
