package se.gory_moon.lj.commands;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tterrag.k9.K9;
import com.tterrag.k9.commands.api.Command;
import com.tterrag.k9.commands.api.CommandContext;
import com.tterrag.k9.commands.api.CommandPersisted;
import com.tterrag.k9.commands.api.Flag;
import com.tterrag.k9.util.Requirements;
import discord4j.core.object.util.Permission;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import reactor.core.publisher.Mono;
import se.gory_moon.lj.LeaveJoinListener;

import java.io.File;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Command
public class CommandLJSetup extends CommandPersisted<CommandLJSetup.JLData> {

    @Setter
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PACKAGE)
    public static class JLData {
        private Long channelId = 0L;
        private boolean logJoin = true;
        private boolean logLeave = true;
        private boolean logRename = true;
    }

    private static final Flag FLAG_ENABLE = new SimpleFlag('e', "enable", "Either `join`, `leave` or `rename`", true);
    private static final Flag FLAG_DISABLE = new SimpleFlag('d', "disable", "Either `join`, `leave`  or `rename`", true);

    private static final Map<String, Pair<Function<JLData, Boolean>, BiConsumer<JLData, Boolean>>> logCommands = new Object2ObjectArrayMap<>();

    public CommandLJSetup() {
        super("jlevent", false, JLData::new);
        logCommands.put("join", Pair.of(JLData::isLogJoin, JLData::setLogJoin));
        logCommands.put("leave", Pair.of(JLData::isLogLeave, JLData::setLogLeave));
        logCommands.put("rename", Pair.of(JLData::isLogRename, JLData::setLogRename));
    }

    @Override
    protected TypeToken<JLData> getDataType() {
        return TypeToken.get(JLData.class);
    }

    @Override
    public Mono<?> process(CommandContext ctx) {
        if (!ctx.getGuildId().isPresent()) {
            return ctx.error("Cannot log `join`/`leave`/`rename` in DMs!");
        }

        boolean disable = ctx.hasFlag(FLAG_DISABLE);
        boolean enable = ctx.hasFlag(FLAG_ENABLE);
        if (disable || enable) {
            String val = disable ? ctx.getFlag(FLAG_DISABLE): ctx.getFlag(FLAG_ENABLE);
            return sanitizeVal(val, ctx).flatMap($ -> storage.get(ctx).map(data -> disableOrEnable(val, data, enable, ctx)).get());
        }
        storage.get(ctx).ifPresent(data -> data.setChannelId(ctx.getChannelId().asLong()));
        return ctx.reply("Channel to log `join`/`leave`/`rename` events set to this");
    }

    @Override
    public String getDescription(CommandContext ctx) {
        return "Setup the `join`/`leave`/`rename` channel for this guild.\nLeave with no flags to set channel id to use this in. Only one channel per guild can have this.";
    }

    private Mono<Boolean> sanitizeVal(String val, CommandContext ctx) {
        return Mono.just("join".equals(val) || "leave".equals(val) || "rename".equals(val))
                .filter(b -> b)
                .switchIfEmpty(ctx.error("Bad flag value, can only be `join`, `leave` or `rename`"));
    }

    private Mono<?> disableOrEnable(String type, JLData data, boolean enable, CommandContext ctx) {
        Pair<Function<JLData, Boolean>, BiConsumer<JLData, Boolean>> dataPair = logCommands.get(type);
        if (dataPair.getLeft().apply(data) != enable) {
            dataPair.getRight().accept(data, enable);
            return ctx.reply((enable ? "Enabled" : "Disabled")  + " `" + type + "` logging in this channel");
        } else{
            return ctx.reply("`" + StringUtils.capitalize(type) + "` logging is already " + (enable ? "enabled" : "disabled") + " in the channel");
        }
    }

    @Override
    public void init(K9 client, File dataFolder, Gson gson) {
        super.init(client, dataFolder, gson);
        LeaveJoinListener.channelId = value -> storage.get(value).getChannelId();
        LeaveJoinListener.logJoin = value -> storage.get(value).isLogJoin();
        LeaveJoinListener.logLeave = value -> storage.get(value).isLogLeave();
        LeaveJoinListener.logRename = value -> storage.get(value).isLogRename();
    }

    @Override
    public Requirements requirements() {
        return Requirements.builder().with(Permission.MANAGE_GUILD, Requirements.RequiredType.ALL_OF).build();
    }

}
