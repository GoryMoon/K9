package se.gory_moon.lj;

import com.tterrag.k9.K9;
import com.tterrag.k9.util.ServiceManager;
import discord4j.common.util.Snowflake;
import discord4j.core.event.domain.guild.MemberJoinEvent;
import discord4j.core.event.domain.guild.MemberLeaveEvent;
import discord4j.core.event.domain.guild.MemberUpdateEvent;
import discord4j.core.object.audit.ActionType;
import discord4j.core.object.audit.AuditLogEntry;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.GuildChannel;
import discord4j.core.object.entity.channel.TextChannel;
import org.apache.commons.lang3.tuple.Pair;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.function.Function;

public enum LeaveJoinListener {
    INSTANCE;

    public static Function<Snowflake, Long> channelId = value -> 0L;
    public static Function<Snowflake, Boolean> logJoin = value -> true;
    public static Function<Snowflake, Boolean> logLeave = value -> true;
    public static Function<Snowflake, Boolean> logRename = value -> true;

    public void init(K9 client, ServiceManager service) {
        client.getCommands().slurpCommands("se.gory_moon.lj.commands");

        service
                .eventService("LogLeave", MemberLeaveEvent.class, events -> events
                        .flatMap(this::onLeave))
                .eventService("LogJoin", MemberJoinEvent.class, events -> events
                        .flatMap(this::onJoin))
                .eventService("LogUpdate", MemberUpdateEvent.class, events -> events
                        .flatMap(this::onRename));
    }

    private Mono<MemberLeaveEvent> onLeave(MemberLeaveEvent event) {
        return event.getGuild()
                .filter($ -> isEnabled(event.getGuildId()))
                .filter($ -> !event.getUser().isBot() && logLeave.apply($.getId()))
                .flatMap(guild -> getGuildChannel(guild).flatMap(channel -> Mono.just(Pair.of(guild, channel))))
                .flatMap(pair -> Flux.merge(
                        auditResponse(event.getUser(), pair.getRight(), pair.getLeft().getAuditLog(spec -> spec.setLimit(1).setActionType(ActionType.MEMBER_KICK))),
                        logLJEvent("`--`", "has left the server.", event.getUser(), pair.getRight())
                ).then())
                .thenReturn(event);
    }

    private Mono<Void> auditResponse(User user, Channel guildChannel, Flux<AuditLogEntry> entryFlux) {
        return entryFlux
                .filter(e -> user.getId().equals(e.getTargetId().orElse(null)))
                .take(1)
                .flatMap(entry -> {
                    if (guildChannel.getType() == Channel.Type.GUILD_TEXT) {
                        TextChannel channel = (TextChannel) guildChannel;
                        return channel.createMessage(spec -> spec.setContent(":monoFight1::monoFight2:")).then();
                    }
                    return Mono.empty();
                }).then();
    }

    private Mono<MemberJoinEvent> onJoin(MemberJoinEvent event) {
        return event.getGuild()
                .filter($ -> isEnabled(event.getGuildId()))
                .filter($ -> !event.getMember().isBot() && logJoin.apply($.getId()))
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
