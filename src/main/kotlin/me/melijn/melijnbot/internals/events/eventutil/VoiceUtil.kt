package me.melijn.melijnbot.internals.events.eventutil

import me.melijn.melijnbot.Container
import me.melijn.melijnbot.commands.music.NextSongPosition
import me.melijn.melijnbot.database.DaoManager
import me.melijn.melijnbot.internals.utils.checks.getAndVerifyMusicChannel
import me.melijn.melijnbot.internals.utils.listeningMembers
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.VoiceChannel
import net.dv8tion.jda.api.events.StatusChangeEvent
import net.dv8tion.jda.api.sharding.ShardManager

object VoiceUtil {

    //guildId, timeOfLeave
    var disconnectQueue = mutableMapOf<Long, Long>()

    suspend fun channelUpdate(container: Container, channelUpdate: VoiceChannel) {
        val guild = channelUpdate.guild
        val botChannel = container.lavaManager.getConnectedChannel(guild)
        val daoManager = container.daoManager

        // Leave channel timer stuff
        botChannel?.let {
            checkShouldDisconnectAndApply(it, daoManager)
        }

        // Radio stuff
        val musicChannel = guild.getAndVerifyMusicChannel(daoManager, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)
            ?: return

        val musicUrl = daoManager.streamUrlWrapper.getUrl(guild.idLong)
        if (musicUrl.isBlank()) return

        val musicPlayerManager = container.lavaManager.musicPlayerManager
        val trackManager = musicPlayerManager.getGuildMusicPlayer(guild).guildTrackManager
        val audioLoader = musicPlayerManager.audioLoader
        val iPlayer = trackManager.iPlayer

        if (musicChannel.id == botChannel?.id && channelUpdate.id == botChannel.id && iPlayer.playingTrack != null) {
            return
        } else if (musicChannel.id == botChannel?.id && channelUpdate.id == botChannel.id) {
            audioLoader.loadNewTrack(
                daoManager,
                container.lavaManager,
                channelUpdate,
                guild.jda.selfUser,
                musicUrl,
                NextSongPosition.BOTTOM
            )

        } else if (botChannel == null && musicChannel.id == channelUpdate.id) {
            if (listeningMembers(musicChannel, container.settings.botInfo.id) > 0) {
                val groupId = trackManager.groupId
                if (container.lavaManager.tryToConnectToVCSilent(musicChannel, groupId)) {
                    audioLoader.loadNewTrack(
                        daoManager,
                        container.lavaManager,
                        channelUpdate,
                        guild.jda.selfUser,
                        musicUrl,
                        NextSongPosition.BOTTOM
                    )
                }
            }
        }
    }

    suspend fun checkShouldDisconnectAndApply(botChannel: VoiceChannel, daoManager: DaoManager) {
        val guildId = botChannel.guild.idLong
        if (
            listeningMembers(botChannel) == 0 &&
            !(daoManager.music247Wrapper.is247Mode(guildId) &&
                daoManager.supporterWrapper.getUsers().contains(guildId))
        ) {
            if (!disconnectQueue.containsKey(guildId)) {
                disconnectQueue[guildId] = System.currentTimeMillis() + 600_000
            }
        } else {
            disconnectQueue.remove(guildId)
        }
    }

    fun getConnectedChannelsAmount(shardManager: ShardManager, andHasListeners: Boolean = false): Long {
        return shardManager.shards.stream().mapToLong { shard ->
            getConnectedChannelsAmount(shard, andHasListeners)
        }?.sum() ?: 0
    }

    fun getConnectedChannelsAmount(inShard: JDA, andHasListeners: Boolean = false): Long {
        return inShard.voiceChannels.stream().filter { vc ->
            val contains = vc.members.contains(vc.guild.selfMember)
            val lm = listeningMembers(vc)
            if (andHasListeners) {
                contains && lm > 0
            } else {
                contains
            }
        }.count()
    }

    suspend fun resumeMusic(event: StatusChangeEvent, container: Container) {
        val wrapper = container.daoManager.tracksWrapper
        val music = wrapper.getMap()
        val channelMap = wrapper.getChannels()
        val shardManager = event.jda.shardManager ?: return
        val mpm = container.lavaManager.musicPlayerManager
        for ((guildId, tracks) in music) {
            val guild = shardManager.getGuildById(guildId) ?: continue
            val channel = channelMap[guildId]?.let { guild.getVoiceChannelById(it) } ?: continue

            val groupId = container.lavaManager.musicPlayerManager.getGuildMusicPlayer(guild).groupId
            if (container.lavaManager.tryToConnectToVCSilent(channel, groupId)) {
                val mp = mpm.getGuildMusicPlayer(guild)
                for (track in tracks) {
                    mp.safeQueueSilent(container.daoManager, track, NextSongPosition.BOTTOM)
                }
            }
        }
        wrapper.clearChannels()
        wrapper.clear()
    }

    suspend fun destroyLink(container: Container, member: Member) {
        container.lavaManager.closeConnection(member.guild.idLong)
    }
}