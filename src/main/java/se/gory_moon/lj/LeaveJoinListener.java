package se.gory_moon.lj;

import com.tterrag.k9.K9;
import discord4j.core.event.domain.guild.MemberJoinEvent;
import discord4j.core.event.domain.guild.MemberLeaveEvent;
import discord4j.core.event.domain.guild.MemberUpdateEvent;
import discord4j.core.object.entity.Channel;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.GuildChannel;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.TextChannel;
import discord4j.core.object.entity.User;
import discord4j.core.object.util.Snowflake;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.function.Function;

public enum LeaveJoinListener {
    INSTANCE;

    public static Function<Snowflake, Long> channelId = value -> 0L;
    public static Function<Snowflake, Boolean> logJoin = value -> true;
    public static Function<Snowflake, Boolean> logLeave = value -> true;
    public static Function<Snowflake, Boolean> logRename = value -> true;

    public Mono<Void> init(K9 client) {
        client.getCommands().slurpCommands("se.gory_moon.lj.commands");

        Mono<Void> onLeave = client.getClient().getEventDispatcher().on(MemberLeaveEvent.class)
                .flatMap(this::onLeave)
                .then();

        Mono<Void> onJoin = client.getClient().getEventDispatcher().on(MemberJoinEvent.class)
                .flatMap(this::onJoin)
                .then();

        Mono<Void> onUpdate = client.getClient().getEventDispatcher().on(MemberUpdateEvent.class)
                .flatMap(this::onRename)
                .then();

        return Mono.zip(onLeave, onJoin, onUpdate).then();
    }

    private Mono<MemberLeaveEvent> onLeave(MemberLeaveEvent event) {
        return event.getGuild()
                .filter($ -> isEnabled(event.getGuildId()))
                .filter($ -> !event.getUser().isBot())
                .filter(guild -> logLeave.apply(guild.getId()))
                .flatMap(this::getGuildChannel)
                .flatMap(channel -> logLJEvent("`--`", "has left the server.", event.getUser(), channel))
                .thenReturn(event);
    }

    private Mono<MemberJoinEvent> onJoin(MemberJoinEvent event) {
        return event.getGuild()
                .filter($ -> isEnabled(event.getGuildId()))
                .filter($ -> !event.getMember().isBot())
                .filter(guild -> logJoin.apply(guild.getId()))
                .flatMap(this::getGuildChannel)
                .flatMap(channel -> logLJEvent("`++`" ,"has joined the server.", event.getMember(), channel))
                .thenReturn(event);
    }

    private Mono<MemberUpdateEvent> onRename(MemberUpdateEvent event) {
        return event.getGuild()
                .filter($ -> isEnabled(event.getGuildId()))
                .filter(guild -> logRename.apply(guild.getId()))
                .filterWhen($ -> event.getMember().map(member -> !member.isBot() && !member.getNickname().equals(event.getOld().flatMap(Member::getNickname))))
                .flatMap(this::getGuildChannel)
                .flatMap(guildChannel -> {
                    if (guildChannel.getType() == Channel.Type.GUILD_TEXT) {
                        TextChannel channel = (TextChannel) guildChannel;
                        String newName;
                        String message;
                        Optional<String> oldNick = event.getOld().flatMap(Member::getNickname);
                        Optional<String> newNick = event.getCurrentNickname();

                        if (!newNick.isPresent() && oldNick.isPresent()) {
                            message = " removed nickname, name back to ";
                            newName = "";
                        } else if (newNick.isPresent() && !oldNick.isPresent()) {
                            message = " renamed to new name ";
                            newName = "'" + newNick.get() + "' ";
                        } else if (newNick.isPresent() && !newNick.equals(oldNick)) {
                            message = " renamed to a different name " ;
                            newName = "'" + newNick.get() + "' ";
                        } else {
                            return Mono.empty();
                        }
                        return channel.typeUntil(event.getMember().flatMap(member -> channel.createMessage(spec -> {
                            String suffix = "`~~` '" + event.getOld().map(Member::getDisplayName).orElse(member.getUsername()) + "'";
                            String postfix = newName + member.getNicknameMention() + "(" + member.getUsername() + "#" + member.getDiscriminator() + ")";
                            spec.setContent(suffix + message + postfix);
                        }))).then();
                    }
                    return Mono.empty();
                })
                .thenReturn(event);
    }

    private Mono<Void> logLJEvent(String prefix, String suffix, User user, GuildChannel guildChannel) {
        if (guildChannel.getType() == Channel.Type.GUILD_TEXT) {
            TextChannel channel = (TextChannel) guildChannel;
            return channel.createMessage(spec -> spec.setContent(prefix + " " + user.getMention() + " (" + user.getUsername() + "#" + user.getDiscriminator() + ") " + suffix)).then();
        }
        return Mono.empty();
    }

    private Mono<GuildChannel> getGuildChannel(Guild guild) {
        return guild.getChannelById(Snowflake.of(channelId.apply(guild.getId())));
    }

    private boolean isEnabled(Snowflake guildId) {
        return channelId.apply(guildId) != 0;
    }
}
