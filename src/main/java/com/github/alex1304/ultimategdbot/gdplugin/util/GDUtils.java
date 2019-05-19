package com.github.alex1304.ultimategdbot.gdplugin.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import com.github.alex1304.jdash.client.AuthenticatedGDClient;
import com.github.alex1304.jdash.entity.GDLevel;
import com.github.alex1304.jdash.entity.GDSong;
import com.github.alex1304.jdash.entity.GDUser;
import com.github.alex1304.jdash.entity.IconType;
import com.github.alex1304.jdash.entity.PrivacySetting;
import com.github.alex1304.jdash.entity.Role;
import com.github.alex1304.jdash.exception.SongNotAllowedForUseException;
import com.github.alex1304.jdash.graphics.SpriteFactory;
import com.github.alex1304.jdash.util.GDPaginator;
import com.github.alex1304.jdash.util.GDUserIconSet;
import com.github.alex1304.jdash.util.Utils;
import com.github.alex1304.ultimategdbot.api.Bot;
import com.github.alex1304.ultimategdbot.api.Command;
import com.github.alex1304.ultimategdbot.api.CommandFailedException;
import com.github.alex1304.ultimategdbot.api.Context;
import com.github.alex1304.ultimategdbot.api.utils.ArgUtils;
import com.github.alex1304.ultimategdbot.api.utils.BotUtils;
import com.github.alex1304.ultimategdbot.api.utils.reply.ReplyMenuBuilder;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLinkedUsers;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDSubscribedGuilds;
import com.github.alex1304.ultimategdbot.gdplugin.leaderboard.LeaderboardEntry;

import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.MessageChannel;
import discord4j.core.object.entity.User;
import discord4j.core.object.util.Snowflake;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

public final class GDUtils {

	private GDUtils() {
	}

	public static final Map<String, String> DIFFICULTY_IMAGES = difficultyImages();
	public static final Map<Integer, String> GAME_VERSIONS = gameVersions();

	public static <T> void addPaginatorItems(ReplyMenuBuilder rb, Command cmd, Context ctx, GDPaginator<T> paginator) {
		if (paginator.hasNextPage()) {
			rb.addItem("next", "To go to next page, type `next`", ctx0 -> {
				ctx.setVar("paginator", paginator.goToNextPage());
				ctx.getBot().getCommandKernel().invokeCommand(cmd, ctx).subscribe();
				return Mono.empty();
			});
		}
		if (paginator.hasPreviousPage()) {
			rb.addItem("prev", "To go to previous page, type `prev`", ctx0 -> {
				ctx.setVar("paginator", paginator.hasPreviousPage());
				ctx.getBot().getCommandKernel().invokeCommand(cmd, ctx).subscribe();
				return Mono.empty();
			});
		}
		if (paginator.getTotalSize() == 0 || paginator.getTotalNumberOfPages() > 1) {
			rb.addItem("page", "To go to a specific page, type `page <number>`, e.g `page 3`", ctx0 -> {
				ArgUtils.requireMinimumArgCount(ctx0, 2, "Please specify a page number");
				var page = ArgUtils.getArgAsInt(ctx0, 1) - 1;
				if (page < 0 || (paginator.getTotalSize() > 0 && page >= paginator.getTotalNumberOfPages())) {
					return Mono.error(new CommandFailedException("Page number out of range"));
				}
				ctx.setVar("paginator", paginator.goTo(page).onErrorMap(IllegalArgumentException.class, __ -> new CommandFailedException("Page number out of range")));
				ctx.getBot().getCommandKernel().invokeCommand(cmd, ctx).subscribe();
				return Mono.empty();
			});
		}
		rb.setHeader("Page " + (paginator.getPageNumber() + 1));
	}
	
	public static Flux<GDSubscribedGuilds> getExistingSubscribedGuilds(Bot bot, String hql) {
		return Mono.zip(bot.getMainDiscordClient().getGuilds().collectList(),
				bot.getDatabase().query(GDSubscribedGuilds.class, "from GDSubscribedGuilds " + hql).collectList())
						.flatMapMany(tuple -> {
							var subSet = new HashSet<>(tuple.getT2());
							var guildIds = tuple.getT1().stream().map(Guild::getId).map(Snowflake::asLong).collect(Collectors.toSet());
							subSet.removeIf(sub -> !guildIds.contains(sub.getGuildId()));
							return Flux.fromIterable(subSet);
						});
	}
	
	public static Mono<Tuple2<Long, Long>> preloadBroadcastChannelsAndRoles(Bot bot, BroadcastPreloader preloader) {
		return Flux.concat(GDUtils.getExistingSubscribedGuilds(bot, "where channelAwardedLevelsId > 0")
						.map(GDSubscribedGuilds::getChannelAwardedLevelsId), 
				GDUtils.getExistingSubscribedGuilds(bot, "where channelTimelyLevelsId > 0")
						.map(GDSubscribedGuilds::getChannelTimelyLevelsId), 
				GDUtils.getExistingSubscribedGuilds(bot, "where channelGdModeratorsId > 0")
						.map(GDSubscribedGuilds::getChannelGdModeratorsId), 
				GDUtils.getExistingSubscribedGuilds(bot, "where channelChangelogId > 0")
						.map(GDSubscribedGuilds::getChannelChangelogId))
				.distinct()
				.map(Snowflake::of)
				.concatMap(preloader::preloadChannel)
				.count()
				.zipWith(Flux.concat(GDUtils.getExistingSubscribedGuilds(bot, "where roleAwardedLevelsId > 0")
						.map(subscribedGuild -> Tuples.of(Snowflake.of(subscribedGuild.getGuildId()), Snowflake.of(subscribedGuild.getRoleAwardedLevelsId()))), 
				GDUtils.getExistingSubscribedGuilds(bot, "where roleTimelyLevelsId > 0")
						.map(subscribedGuild -> Tuples.of(Snowflake.of(subscribedGuild.getGuildId()), Snowflake.of(subscribedGuild.getRoleTimelyLevelsId()))), 
				GDUtils.getExistingSubscribedGuilds(bot, "where roleGdModeratorsId > 0")
						.map(subscribedGuild -> Tuples.of(Snowflake.of(subscribedGuild.getGuildId()), Snowflake.of(subscribedGuild.getRoleGdModeratorsId()))))
				.distinct()
				.concatMap(TupleUtils.function(preloader::preloadRole))
				.count());
	}
	
	// ------------ USER PROFILE UTILS ------------ //
	
	public static Mono<Consumer<MessageCreateSpec>> userProfileView(Bot bot, Optional<User> author, GDUser user, 
			String authorName, String authorIconUrl, String iconUrl, String iconSetUrl) {
		return Mono.zip(o -> o, bot.getEmoji("star"), bot.getEmoji("diamond"), bot.getEmoji("user_coin"),
				bot.getEmoji("secret_coin"), bot.getEmoji("demon"), bot.getEmoji("creator_points"),
				bot.getEmoji("mod"), bot.getEmoji("elder_mod"), bot.getEmoji("global_rank"),
				bot.getEmoji("youtube"), bot.getEmoji("twitter"), bot.getEmoji("twitch"),
				bot.getEmoji("discord"), bot.getEmoji("friends"), bot.getEmoji("messages"),
				bot.getEmoji("comment_history"))
				.zipWith(getDiscordAccountsForGDUser(bot, user).collectList())
				.map(tuple -> {
					var emojis = tuple.getT1();
					var linkedAccounts = tuple.getT2();
					return mcs -> {
						final var statWidth = 9;
						if (author.isPresent()) {
							mcs.setContent(author.get().getMention() + ", here is the profile of user **" + user.getName() + "**:");
						}
						mcs.setEmbed(embed -> {
							embed.setAuthor(authorName, null, authorIconUrl);
							embed.addField(":chart_with_upwards_trend:  " + user.getName() + "'s stats", emojis[0] + "  " + formatCode(user.getStars(), statWidth) + "\n"
									+ emojis[1] + "  " + formatCode(user.getDiamonds(), statWidth) + "\n"
									+ emojis[2] + "  " + formatCode(user.getUserCoins(), statWidth) + "\n"
									+ emojis[3] + "  " + formatCode(user.getSecretCoins(), statWidth) + "\n"
									+ emojis[4] + "  " + formatCode(user.getDemons(), statWidth) + "\n"
									+ emojis[5] + "  " + formatCode(user.getCreatorPoints(), statWidth) + "\n", false);
							final var badge = user.getRole() == Role.ELDER_MODERATOR ? emojis[7] : emojis[6];
							final var mod = badge + "  **" + user.getRole().toString().replaceAll("_", " ") + "**\n";
							embed.addField("───────────", (user.getRole() != Role.USER ? mod : "")
									+ emojis[8] + "  **Global Rank:** "
									+ (user.getGlobalRank() == 0 ? "*Unranked*" : user.getGlobalRank()) + "\n"
									+ emojis[9] + "  **Youtube:** "
										+ (user.getYoutube().isEmpty() ? "*not provided*" : "[Open link](https://www.youtube.com/channel/"
										+ Utils.urlEncode(user.getYoutube()) + ")") + "\n"
									+ emojis[11] + "  **Twitch:** "
										+ (user.getTwitch().isEmpty() ? "*not provided*" : "["  + user.getTwitch()
										+ "](http://www.twitch.tv/" + Utils.urlEncode(user.getTwitch()) + ")") + "\n"
									+ emojis[10] + "  **Twitter:** "
										+ (user.getTwitter().isEmpty() ? "*not provided*" : "[@" + user.getTwitter() + "]"
										+ "(http://www.twitter.com/" + Utils.urlEncode(user.getTwitter()) + ")") + "\n"
									+ emojis[12] + "  **Discord:** " + (linkedAccounts.isEmpty() ? "*unknown*" : linkedAccounts.stream()
											.reduce(new StringJoiner(", "), (sj, l) -> sj.add(BotUtils.formatDiscordUsername(l)), (a, b) -> a).toString())
									+ "\n───────────\n"
									+ emojis[13] + "  **Friend requests:** " + (user.hasFriendRequestsEnabled() ? "Enabled" : "Disabled") + "\n"
									+ emojis[14] + "  **Private messages:** " + formatPrivacy(user.getPrivateMessagePolicy()) + "\n"
									+ emojis[15] + "  **Comment history:** " + formatPrivacy(user.getCommmentHistoryPolicy()) + "\n", false);
							embed.setFooter("PlayerID: " + user.getId() + " | " + "AccountID: " + user.getAccountId(), null);
							embed.setThumbnail(iconUrl);
							embed.setImage(iconSetUrl);
						});
					};
				});
	}
	
	private static String formatPrivacy(PrivacySetting privacy) {
		return  privacy.name().charAt(0) + privacy.name().substring(1).replaceAll("_", " ").toLowerCase();
	}
	
	private static String formatCode(Object val, int n) {
		var sb = new StringBuilder("" + val);
		for (var i = sb.length() ; i <= n ; i++) {
			sb.insert(0, " ‌‌");
		}
		sb.insert(0, '`').append('`');
		return sb.toString();
	}
	
	public static Mono<String[]> makeIconSet(Bot bot, GDUser user, SpriteFactory sf, Map<GDUserIconSet, String[]> iconsCache) {
		final var iconSet = new GDUserIconSet(user, sf);
		final var cached = iconsCache.get(iconSet);
		if (cached != null) {
			return Mono.just(cached);
		}
		final var mainIcon = iconSet.generateIcon(user.getMainIconType());
		final var icons = new ArrayList<BufferedImage>();
		for (var iconType : IconType.values()) {
			icons.add(iconSet.generateIcon(iconType));
		}
		final var iconSetImg = new BufferedImage(icons.stream().mapToInt(BufferedImage::getWidth).sum(), mainIcon.getHeight(), mainIcon.getType());
		final var g = iconSetImg.createGraphics();
		var offset = 0;
		for (var icon : icons) {
			g.drawImage(icon, offset, 0, null);
			offset += icon.getWidth();
		}
		g.dispose();
		final var istreamMain = imageToInputStream(mainIcon);
		final var istreamIconSet = imageToInputStream(iconSetImg);
		
		return bot.getAttachmentsChannel().ofType(MessageChannel.class).flatMap(c -> c.createMessage(mcs -> {
			mcs.addFile(user.getId() + "-Main.png", istreamMain);
			mcs.addFile(user.getId() + "-IconSet.png", istreamIconSet);
		})).map(msg -> {
			var urls = new String[2];
			for (var a : msg.getAttachments()) {
				urls[a.getFilename().endsWith("Main.png") ? 0 : 1] = a.getUrl();
			}
			iconsCache.put(iconSet, urls);
			return urls;
		});
	}
	
	private static InputStream imageToInputStream(BufferedImage img) {
		try {
			final var os = new ByteArrayOutputStream(100_000);
			ImageIO.write(img, "png", os);
			return new ByteArrayInputStream(os.toByteArray());
		} catch (IOException e) {
			throw new UncheckedIOException(e); // Should never happen
		}
	}
	
	public static Mono<GDUser> stringToUser(Bot bot, AuthenticatedGDClient gdClient, String str) {
		if (str.matches("<@!?[0-9]{1,19}>")) {
			var id = str.substring(str.startsWith("<@!") ? 3 : 2, str.length() - 1);
			return Mono.just(id)
					.map(Snowflake::of)
					.onErrorMap(e -> new CommandFailedException("Not a valid mention."))
					.flatMap(snowflake -> bot.getDiscordClients().flatMap(client -> client.getUserById(snowflake)).next())
					.onErrorMap(e -> new CommandFailedException("Could not resolve the mention to a valid user."))
					.flatMap(user -> bot.getDatabase().findByID(GDLinkedUsers.class, user.getId().asLong()))
					.filter(GDLinkedUsers::getIsLinkActivated)
					.flatMap(linkedUser -> gdClient.getUserByAccountId(linkedUser.getGdAccountId()))
					.switchIfEmpty(Mono.error(new CommandFailedException("This user doesn't have an associated Geometry Dash account.")));
		}
		if (!str.matches("[a-zA-Z0-9 _-]+")) {
			return Mono.error(new CommandFailedException("Your query contains invalid characters."));
		}
		return gdClient.searchUser(str);
	}

	// ------------ LEVEL UTILS ------------ //
	
	public static Mono<Consumer<EmbedCreateSpec>> levelPaginatorView(Context ctx, GDPaginator<GDLevel> paginator, String title) {
		return Mono.zip(o -> o, ctx.getBot().getEmoji("copy"), ctx.getBot().getEmoji("object_overflow"), ctx.getBot().getEmoji("downloads"),
				ctx.getBot().getEmoji("like"), ctx.getBot().getEmoji("length"), ctx.getBot().getEmoji("user_coin"),
				ctx.getBot().getEmoji("user_coin_unverified"), ctx.getBot().getEmoji("star"), ctx.getBot().getEmoji("dislike"))
				.zipWith(getLevelDifficultyAndSongFromPaginator(ctx, paginator))
				.map(tuple -> {
					var emojis = tuple.getT1();
					var map = tuple.getT2();
					return embed -> {
						embed.setTitle(title);
						var i = 1;
						for (var level : paginator) {
							var coins = coinsToEmoji("" + emojis[level.hasCoinsVerified() ? 5 : 6], level.getCoinCount(), true);
							var difficultyEmoji = map.get(level).getT1();
							var song = map.get(level).getT2();
							embed.addField(String.format("`%02d` - %s %s | __**%s**__ by **%s** %s%s",
									i,
									difficultyEmoji + (level.getStars() > 0 ? " " + emojis[7] + " x" + level.getStars() : ""),
									coins.equals("None") ? "" : " " + coins,
									level.getName(),
									level.getCreatorName(),
									level.getOriginalLevelID() > 0 ? emojis[0] : "",
									level.getObjectCount() > 40000 ? emojis[1] : ""),
									String.format("%s %d \t\t %s %d \t\t %s %s\n"
											+ ":musical_note:  **%s**\n _ _",
											"" + emojis[2],
											level.getDownloads(),
											"" + (level.getLikes() > 0 ? emojis[3] : emojis[8]),
											level.getLikes(),
											"" + emojis[4],
											"" + level.getLength(),
											song), false);
							i++;
						}
					};
				});
	}
	
	public static Mono<Consumer<EmbedCreateSpec>> levelView(Context ctx, GDLevel level, String authorName, String authorIconUrl) {
		return Mono.zip(o -> o, ctx.getBot().getEmoji("play"), ctx.getBot().getEmoji("downloads"), ctx.getBot().getEmoji("dislike"),
				ctx.getBot().getEmoji("like"), ctx.getBot().getEmoji("length"), ctx.getBot().getEmoji("lock"),
				ctx.getBot().getEmoji("copy"), ctx.getBot().getEmoji("object_overflow"), ctx.getBot().getEmoji("user_coin"),
				ctx.getBot().getEmoji("user_coin_unverified"))
				.zipWith(Mono.zip(level.download(), formatSongPrimaryMetadata(level.getSong()),
						formatSongSecondaryMetadata(ctx, level.getSong())))
				.map(tuple -> {
					final var emojis = tuple.getT1();
					final var data = tuple.getT2().getT1();
					final var songInfo = ":musical_note:   " + tuple.getT2().getT2();
					final var songInfo2 = tuple.getT2().getT3();
					final var dlWidth = 9;
					return embed -> {
						embed.setAuthor(authorName, null, authorIconUrl);
						embed.setThumbnail(getDifficultyImageForLevel(level));
						var title = emojis[0] + "  __" + level.getName() + "__ by " + level.getCreatorName() + "";
						var description = "**Description:** " + (level.getDescription().isEmpty() ? "*(No description provided)*"
								: BotUtils.escapeMarkdown(level.getDescription()));
						var coins = "Coins: " + coinsToEmoji("" + emojis[level.hasCoinsVerified() ? 8 : 9], level.getCoinCount(), false);
						var downloadLikesLength = emojis[1] + " " + formatCode(level.getDownloads(), dlWidth) + "\n"
								+ (level.getLikes() < 0 ? emojis[2] + " " : emojis[3] + " ") + formatCode(level.getLikes(), dlWidth) + "\n"
								+ emojis[4] + " " + formatCode(level.getLength(), dlWidth);
						var objCount = "**Object count:** ";
						if (level.getObjectCount() > 0 || level.getLevelVersion() >= 21) {
							if (level.getObjectCount() == 65535)
								objCount += ">";
							objCount += level.getObjectCount();
						} else
							objCount += "_Unknown_";
						objCount += "\n";
						var extraInfo = new StringBuilder();
						extraInfo.append("**Level ID:** " + level.getId() + "\n");
						extraInfo.append("**Level version:** " + level.getLevelVersion() + "\n");
						extraInfo.append("**Minimum GD version required to play this level:** " + formatGameVersion(level.getGameVersion()) + "\n");
						extraInfo.append(objCount);
						var pass = "";
						if (data.getPass() == -2)
							pass = "Yes, no passcode required";
						else if (data.getPass() == -1)
							pass = "No";
						else
							pass = "Yes, " + emojis[5] + " passcode: " + String.format("||%06d||", data.getPass());
						extraInfo.append("**Copyable:** " + pass + "\n");
						extraInfo.append("**Uploaded:** " + data.getUploadTimestamp() + " ago\n");
						extraInfo.append("**Last updated:** " + data.getLastUpdatedTimestamp() + " ago\n");
						if (level.getOriginalLevelID() > 0)
							extraInfo.append(emojis[6] + " **Original:** " + level.getOriginalLevelID() + "\n");
						if (level.getObjectCount() > 40000)
							extraInfo.append(emojis[7] + " **This level may lag on low end devices**\n");
						embed.addField(title, description, false);
						embed.addField(coins, downloadLikesLength + "\n_ _", false);
						embed.addField(songInfo, songInfo2 + "\n_ _\n" + extraInfo, false);
					};
				})
				;
	}
	
	public static Mono<Consumer<EmbedCreateSpec>> shortLevelView(Bot bot, GDLevel level, String authorName, String authorIconUrl) {
		return Mono.zip(o -> o, bot.getEmoji("play"), bot.getEmoji("downloads"), bot.getEmoji("dislike"),
				bot.getEmoji("like"), bot.getEmoji("length"), bot.getEmoji("copy"),
				bot.getEmoji("object_overflow"), bot.getEmoji("user_coin"), bot.getEmoji("user_coin_unverified"))
				.zipWith(formatSongPrimaryMetadata(level.getSong()))
				.map(tuple -> {
					final var emojis = tuple.getT1();
					final var songInfo = ":musical_note:   " + tuple.getT2();
					final var dlWidth = 9;
					return embed -> {
						embed.setAuthor(authorName, null, authorIconUrl);
						embed.setThumbnail(getDifficultyImageForLevel(level));
						var title = emojis[0] + "  __" + level.getName() + "__ by " + level.getCreatorName() + "" +
								(level.getOriginalLevelID() > 0 ? " " + emojis[5] : "") +
								(level.getObjectCount() > 40_000 ? " " + emojis[6] : "");
						var coins = "Coins: " + coinsToEmoji("" + emojis[level.hasCoinsVerified() ? 7 : 8], level.getCoinCount(), false);
						var downloadLikesLength = emojis[1] + " " + formatCode(level.getDownloads(), dlWidth) + "\n"
								+ (level.getLikes() < 0 ? emojis[2] + " " : emojis[3] + " ") + formatCode(level.getLikes(), dlWidth) + "\n"
								+ emojis[4] + " " + formatCode(level.getLength(), dlWidth);
						embed.addField(title, downloadLikesLength, false);
						embed.addField(coins, songInfo, false);
						embed.setFooter("LevelID: " + level.getId(), null);
					};
				});
	}
	
	private static String getDifficultyImageForLevel(GDLevel level) {
		var difficulty = new StringBuilder();
		difficulty.append(level.getStars()).append("-");
		if (level.isDemon())
			difficulty.append("demon-").append(level.getDemonDifficulty().toString().toLowerCase());
		else if (level.isAuto())
			difficulty.append("auto");
		else
			difficulty.append(level.getDifficulty().toString().toLowerCase());
		if (level.isEpic())
			difficulty.append("-epic");
		else if (level.getFeaturedScore() > 0)
			difficulty.append("-featured");
		return DIFFICULTY_IMAGES.getOrDefault(difficulty.toString(), "https://i.imgur.com/T3YfK5d.png");
	}
	
	public static String formatGameVersion(int v) {
		if (v < 10)
			return "<1.6";
		if (GAME_VERSIONS.containsKey(v))
			return GAME_VERSIONS.get(v);
		
		var vStr = String.format("%02d", v);
		if (vStr.length() <= 1)
			return vStr;
		
		return vStr.substring(0, vStr.length() - 1) + "." + vStr.charAt(vStr.length() - 1);
	}
	
	private static String coinsToEmoji(String emoji, int n, boolean shorten) {
		final var output = new StringBuilder();
		if (shorten) {
			if (n <= 0)
				return "";
			output.append(emoji);
			output.append(" x");
			output.append(n);
		} else {
			if (n <= 0)
				return "None";
			
			for (int i = 1 ; i <= n && i <= 3 ; i++) {
				output.append(emoji);
				output.append(" ");
			}
		}
		
		return output.toString();
	}
	
	private static Mono<Map<GDLevel, Tuple2<String, String>>> getLevelDifficultyAndSongFromPaginator(Context ctx, GDPaginator<GDLevel> levels) {
		return ctx.getBot().getEmoji("star")
				.flatMap(starEmoji -> Flux.fromIterable(levels)
						.flatMap(level -> {
							var difficulty = new StringBuilder("icon_");
							if (level.isDemon())
								difficulty.append("demon_").append(level.getDemonDifficulty().toString());
							else if (level.isAuto())
								difficulty.append("auto");
							else
								difficulty.append(level.getDifficulty().toString());
							if (level.isEpic())
								difficulty.append("_epic");
							else if (level.getFeaturedScore() > 0)
								difficulty.append("_featured");
							return formatSongPrimaryMetadata(level.getSong())
									.map(songFormat -> Tuples.of(level, Tuples.of(difficulty.toString(), songFormat)));
									
						})
						
						.flatMap(tuple -> ctx.getBot().getEmoji(tuple.getT2().getT1())
								.map(emoji -> Tuples.of(tuple.getT1(), Tuples.of(emoji, tuple.getT2().getT2()))))
						.collectMap(Tuple2::getT1, Tuple2::getT2));
	}
	
	public static Mono<String> formatSongPrimaryMetadata(Mono<GDSong> monoSong) {
		return monoSong.map(song -> "__" + song.getSongTitle() + "__ by " + song.getSongAuthorName())
				.onErrorReturn(SongNotAllowedForUseException.class, ":warning: Song is not allowed for use")
				.onErrorReturn(":warning: Unknown song");
	}

	public static Mono<String> formatSongSecondaryMetadata(Context ctx, Mono<GDSong> monoSong) {
		return Mono.zip(ctx.getBot().getEmoji("play"), ctx.getBot().getEmoji("download_song"))
				.flatMap(emojis -> {
					final var ePlay = emojis.getT1();
					final var eDlSong = emojis.getT2();
					return monoSong.map(song -> song.isCustom()
							? "SongID: " + song.getId() + " - Size: " + song.getSongSize() + "MB\n" + ePlay
									+ " [Play on Newgrounds](https://www.newgrounds.com/audio/listen/" + song.getId() + ")  "
									+ eDlSong + " [Download MP3](" + song.getDownloadURL() + ")"
							: "Geometry Dash native audio track").onErrorReturn("Song info unavailable");
				});
	}
	
	public static String levelToString(GDLevel level) {
		return "__"+ level.getName() + "__ by " + level.getCreatorName() + " (" + level.getId() + ")";
	}
	
	// ============ ACCOUNT LINKING ==============
	
	/**
	 * Generates a random String made of alphanumeric characters. The length of the
	 * generated String is specified as an argument.
	 * 
	 * The following characters are excluded to avoid confusion between l and 1, O
	 * and 0, etc: <code>l, I, 1, 0, O</code>
	 * 
	 * @param n the length of the generated String
	 * @return the generated random String
	 */
	public static String generateAlphanumericToken(int n) {
		if (n < 1) {
			throw new IllegalArgumentException("n is negative");
		}
		
		final String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
		char[] result = new char[n];
		
		for (int i = 0 ; i < result.length ; i++)
			result[i] = alphabet.charAt(new Random().nextInt(alphabet.length()));
		
		return new String(result);
	}
	
	public static Flux<User> getDiscordAccountsForGDUser(Bot bot, GDUser gdUser) {
		return bot.getDatabase().query(GDLinkedUsers.class, "from GDLinkedUsers linkedUser where linkedUser.gdAccountId = ?0 "
				+ "and linkedUser.isLinkActivated = 1", gdUser.getAccountId())
				.flatMap(linkedUser -> bot.getDiscordClients()
						.flatMap(client -> client.getUserById(Snowflake.of(linkedUser.getDiscordUserId())))
						.take(1));
	}

	public static Mono<Consumer<EmbedCreateSpec>> leaderboardView(Context ctx, List<LeaderboardEntry> subList, int page,
			int elementsPerPage, int size) {
		var highlighted = ctx.getVar("highlighted", String.class);
		return ctx.getEvent().getGuild().map(guild -> embed -> {
			embed.setTitle("Geometry Dash leaderboard for server __" + guild.getName() + "__");
			if (size == 0 || subList.isEmpty()) {
				embed.setDescription("No entries.");
				return;
			}
			var sb = new StringBuilder();
			var rankWidth = (int) Math.log10(size) + 1;
			var statWidth = (int) Math.log10(subList.get(0).getValue()) + 1;
			final var maxRowLength = 100;
			for (var i = 1 ; i <= subList.size() ; i++) {
				var entry = subList.get(i - 1);
				var isHighlighted = entry.getGdUser().getName().equalsIgnoreCase(highlighted);
				var rank = page * elementsPerPage + i;
				if (isHighlighted) {
					sb.append("**");
				}
				var row = String.format("%s | %s %s | %s (%s)",
						String.format("`#%" + rankWidth + "d`", rank).replaceAll(" ", " ‌‌"),
						entry.getEmoji(),
						formatCode(entry.getValue(), statWidth),
						entry.getGdUser().getName(),
						entry.getDiscordUser());
				if (row.length() > maxRowLength) {
					row = row.substring(0, maxRowLength - 3) + "...";
				}
				sb.append(row).append("\n");
				if (isHighlighted) {
					sb.append("**");
				}
			}
			embed.setDescription("**Total players: " + size + ", " + subList.get(0).getEmoji() + " leaderboard**\n\n" + sb.toString());
			embed.addField(" ‌‌", "Note that members of this server must have linked their Geometry Dash account with `"
					+ ctx.getPrefixUsed() + "account` in order to be displayed on this leaderboard.", false);
		});
	}
	
	// =============================================================
	
	private static Map<Integer, String> gameVersions() {
		var map = new HashMap<Integer, String>();
		map.put(10, "1.7");
		map.put(11, "1.8");
		return Collections.unmodifiableMap(map);
	}

	private static Map<String, String> difficultyImages() {
		var map = new HashMap<String, String>();
		map.put("6-harder-featured", "https://i.imgur.com/b7J4AXi.png");
		map.put("0-insane-epic", "https://i.imgur.com/GdS2f8f.png");
		map.put("0-harder", "https://i.imgur.com/5lT74Xj.png");
		map.put("4-hard-epic", "https://i.imgur.com/toyo1Cd.png");
		map.put("4-hard", "https://i.imgur.com/XnUynAa.png");
		map.put("6-harder", "https://i.imgur.com/e499HCB.png");
		map.put("5-hard-epic", "https://i.imgur.com/W11eyJ9.png");
		map.put("6-harder-epic", "https://i.imgur.com/9x1ddvD.png");
		map.put("5-hard", "https://i.imgur.com/Odx0nAT.png");
		map.put("1-auto-featured", "https://i.imgur.com/DplWGja.png");
		map.put("5-hard-featured", "https://i.imgur.com/HiyX5DD.png");
		map.put("8-insane-featured", "https://i.imgur.com/PYJ5T0x.png");
		map.put("0-auto-featured", "https://i.imgur.com/eMwuWmx.png");
		map.put("8-insane", "https://i.imgur.com/RDVJDaO.png");
		map.put("7-harder-epic", "https://i.imgur.com/X3N5sm1.png");
		map.put("0-normal-epic", "https://i.imgur.com/VyV8II6.png");
		map.put("0-demon-hard-featured", "https://i.imgur.com/lVdup3A.png");
		map.put("8-insane-epic", "https://i.imgur.com/N2pjW2W.png");
		map.put("3-normal-epic", "https://i.imgur.com/S3PhlDs.png");
		map.put("0-normal-featured", "https://i.imgur.com/Q1MYgu4.png");
		map.put("2-easy", "https://i.imgur.com/yG1U6RP.png");
		map.put("0-hard-featured", "https://i.imgur.com/8DeaxfL.png");
		map.put("0-demon-hard-epic", "https://i.imgur.com/xLFubIn.png");
		map.put("1-auto", "https://i.imgur.com/Fws2s3b.png");
		map.put("0-demon-hard", "https://i.imgur.com/WhrTo7w.png");
		map.put("0-easy", "https://i.imgur.com/kWHZa5d.png");
		map.put("2-easy-featured", "https://i.imgur.com/Kyjevk1.png");
		map.put("0-insane-featured", "https://i.imgur.com/t8JmuIw.png");
		map.put("0-hard", "https://i.imgur.com/YV4Afz2.png");
		map.put("0-na", "https://i.imgur.com/T3YfK5d.png");
		map.put("7-harder", "https://i.imgur.com/dJoUDUk.png");
		map.put("0-na-featured", "https://i.imgur.com/C4oMYGU.png");
		map.put("3-normal", "https://i.imgur.com/cx8tv98.png");
		map.put("0-harder-featured", "https://i.imgur.com/n5kA2Tv.png");
		map.put("0-harder-epic", "https://i.imgur.com/Y7bgUu9.png");
		map.put("0-na-epic", "https://i.imgur.com/hDBDGzX.png");
		map.put("1-auto-epic", "https://i.imgur.com/uzYx91v.png");
		map.put("0-easy-featured", "https://i.imgur.com/5p9eTaR.png");
		map.put("0-easy-epic", "https://i.imgur.com/k2lJftM.png");
		map.put("0-hard-epic", "https://i.imgur.com/SqnA9kJ.png");
		map.put("3-normal-featured", "https://i.imgur.com/1v3p1A8.png");
		map.put("0-normal", "https://i.imgur.com/zURUazz.png");
		map.put("6-harder-featured", "https://i.imgur.com/b7J4AXi.png");
		map.put("2-easy-epic", "https://i.imgur.com/wl575nH.png");
		map.put("7-harder-featured", "https://i.imgur.com/v50cZBZ.png");
		map.put("0-auto", "https://i.imgur.com/7xI8EOp.png");
		map.put("0-insane", "https://i.imgur.com/PeOvWuq.png");
		map.put("4-hard-featured", "https://i.imgur.com/VW4yufj.png");
		map.put("0-auto-epic", "https://i.imgur.com/QuRBnpB.png");
		map.put("10-demon-hard", "https://i.imgur.com/jLBD7cO.png");
		map.put("9-insane-featured", "https://i.imgur.com/byhPbgR.png");
		map.put("10-demon-hard-featured", "https://i.imgur.com/7deDmTQ.png");
		map.put("10-demon-hard-epic", "https://i.imgur.com/xtrTl4r.png");
		map.put("9-insane", "https://i.imgur.com/5VA2qDb.png");
		map.put("9-insane-epic", "https://i.imgur.com/qmfey5L.png");
		// Demon difficulties
		map.put("0-demon-medium-epic", "https://i.imgur.com/eEEzM6I.png");
		map.put("10-demon-medium-epic", "https://i.imgur.com/ghco42q.png");
		map.put("10-demon-insane", "https://i.imgur.com/nLZqoyQ.png");
		map.put("0-demon-extreme-epic", "https://i.imgur.com/p250YUh.png");
		map.put("0-demon-easy-featured", "https://i.imgur.com/r2WNVw0.png");
		map.put("10-demon-easy", "https://i.imgur.com/0zM0VuT.png");
		map.put("10-demon-medium", "https://i.imgur.com/lvpPepA.png");
		map.put("10-demon-insane-epic", "https://i.imgur.com/2BWY8pO.png");
		map.put("10-demon-medium-featured", "https://i.imgur.com/kkAZv5O.png");
		map.put("0-demon-extreme-featured", "https://i.imgur.com/4MMF8uE.png");
		map.put("0-demon-extreme", "https://i.imgur.com/v74cX5I.png");
		map.put("0-demon-medium", "https://i.imgur.com/H3Swqhy.png");
		map.put("0-demon-medium-featured", "https://i.imgur.com/IaeyGY4.png");
		map.put("0-demon-insane", "https://i.imgur.com/fNC1iFH.png");
		map.put("0-demon-easy-epic", "https://i.imgur.com/idesUcS.png");
		map.put("10-demon-easy-epic", "https://i.imgur.com/wUGOGJ7.png");
		map.put("10-demon-insane-featured", "https://i.imgur.com/RWqIpYL.png");
		map.put("10-demon-easy-featured", "https://i.imgur.com/fFq5lbN.png");
		map.put("0-demon-insane-featured", "https://i.imgur.com/1MpbSRR.png");
		map.put("0-demon-insane-epic", "https://i.imgur.com/ArGfdeh.png");
		map.put("10-demon-extreme", "https://i.imgur.com/DEr1HoM.png");
		map.put("0-demon-easy", "https://i.imgur.com/45GaxRN.png");
		map.put("10-demon-extreme-epic", "https://i.imgur.com/gFndlkZ.png");
		map.put("10-demon-extreme-featured", "https://i.imgur.com/xat5en2.png");
		return Collections.unmodifiableMap(map);
	}
}