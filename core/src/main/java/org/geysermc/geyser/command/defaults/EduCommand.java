/*
 * Copyright (c) 2019-2024 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.command.defaults;

import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.util.TriState;
import org.geysermc.geyser.command.GeyserCommand;
import org.geysermc.geyser.command.GeyserCommandSource;
import org.geysermc.geyser.network.EducationAuthManager;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.text.ChatColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;

import java.time.Instant;

public class EduCommand extends GeyserCommand {

    private final GeyserImpl geyser;

    public EduCommand(GeyserImpl geyser, String name, String description, String permission) {
        super(name, description, permission, TriState.NOT_SET);
        this.geyser = geyser;
    }

    @Override
    public void register(CommandManager<GeyserCommandSource> manager) {
        // /geyser edu (no args = status)
        manager.command(baseBuilder(manager).handler(this::execute));

        // /geyser edu status
        manager.command(baseBuilder(manager)
                .literal("status")
                .handler(this::executeStatus));

        // /geyser edu players
        manager.command(baseBuilder(manager)
                .literal("players")
                .handler(this::executePlayers));

        // /geyser edu reset
        manager.command(baseBuilder(manager)
                .literal("reset")
                .handler(this::executeReset));
    }

    @Override
    public void execute(CommandContext<GeyserCommandSource> context) {
        executeStatus(context);
    }

    private void executeStatus(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();
        EducationAuthManager eduAuth = geyser.getEducationAuthManager();

        source.sendMessage(ChatColor.AQUA + "=== Education Edition Status ===");

        if (eduAuth == null || !eduAuth.isActive()) {
            source.sendMessage(ChatColor.YELLOW + "Education system: " + ChatColor.RED + "INACTIVE");
            source.sendMessage(ChatColor.GRAY + "Configure edu-server-id or edu-server-name in config.yml to enable.");
            return;
        }

        source.sendMessage(ChatColor.YELLOW + "Education system: " + ChatColor.GREEN + "ACTIVE");
        source.sendMessage(ChatColor.YELLOW + "Server ID: " + ChatColor.WHITE + eduAuth.getServerId());
        source.sendMessage(ChatColor.YELLOW + "Server IP: " + ChatColor.WHITE + eduAuth.getServerIp());

        long expires = eduAuth.getServerTokenExpires();
        long now = Instant.now().getEpochSecond();
        String expiryStr = eduAuth.formatExpiryPublic(expires);
        if (expires > now) {
            long remaining = expires - now;
            source.sendMessage(ChatColor.YELLOW + "Token expires: " + ChatColor.WHITE + expiryStr
                    + ChatColor.GRAY + " (in " + remaining + "s)");
        } else {
            source.sendMessage(ChatColor.YELLOW + "Token expires: " + ChatColor.RED + "EXPIRED (" + expiryStr + ")");
        }

        // Count education players
        int eduCount = 0;
        int totalCount = 0;
        for (GeyserSession session : geyser.onlineConnections()) {
            totalCount++;
            if (session.isEducationClient()) {
                eduCount++;
            }
        }
        source.sendMessage(ChatColor.YELLOW + "Education players: " + ChatColor.WHITE + eduCount + "/" + totalCount + " online");
        source.sendMessage(ChatColor.YELLOW + "Auth mode: " + ChatColor.WHITE + geyser.config().eduAuthMode());
    }

    private void executePlayers(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();

        source.sendMessage(ChatColor.AQUA + "=== Education Edition Players ===");

        int count = 0;
        for (GeyserSession session : geyser.onlineConnections()) {
            if (session.isEducationClient()) {
                count++;
                String name = session.bedrockUsername();
                String tenantId = session.getEducationTenantId() != null
                        ? session.getEducationTenantId() : "unknown";
                int adRole = session.getClientData() != null ? session.getClientData().getAdRole() : -1;
                String roleName = switch (adRole) {
                    case 0 -> "student";
                    case 1 -> "teacher";
                    default -> "role=" + adRole;
                };
                source.sendMessage(ChatColor.WHITE + "  " + name + ChatColor.GRAY
                        + " (tenant: " + tenantId + ", " + roleName + ")");
            }
        }

        if (count == 0) {
            source.sendMessage(ChatColor.GRAY + "No Education Edition players connected.");
        } else {
            source.sendMessage(ChatColor.YELLOW + "Total: " + count + " education player(s)");
        }
    }

    private void executeReset(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();
        EducationAuthManager eduAuth = geyser.getEducationAuthManager();

        if (eduAuth == null) {
            source.sendMessage(ChatColor.RED + "Education system is not initialized.");
            return;
        }

        source.sendMessage(ChatColor.YELLOW + "Resetting education session and re-authenticating...");
        source.sendMessage(ChatColor.GRAY + "This will delete the current session and start a new device code flow.");
        source.sendMessage(ChatColor.GRAY + "Check the console for the authentication code.");
        eduAuth.resetAndReauthenticate();
    }
}
