package com.github.alex1304.ultimategdbot.gdplugin.command;

import com.github.alex1304.jdash.entity.GDUser;
import com.github.alex1304.ultimategdbot.api.command.CommandFailedException;
import com.github.alex1304.ultimategdbot.api.command.Context;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandAction;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandDescriptor;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandDoc;
import com.github.alex1304.ultimategdbot.api.database.DatabaseService;
import com.github.alex1304.ultimategdbot.api.util.MessageSpecTemplate;
import com.github.alex1304.ultimategdbot.gdplugin.GDService;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLinkedUserDao;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLinkedUserData;
import com.github.alex1304.ultimategdbot.gdplugin.util.GDUsers;

import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

@CommandDescriptor(
		aliases = "profile",
		shortDescription = "tr:GDStrings/profile_desc"
)
public class ProfileCommand {

	@CommandAction
	@CommandDoc("tr:GDStrings/profile_run")
	public Mono<Void> run(Context ctx, @Nullable GDUser gdUser) {
		return Mono.justOrEmpty(gdUser)
				.switchIfEmpty(ctx.bot().service(DatabaseService.class)
						.withExtension(GDLinkedUserDao.class, dao -> dao.getByDiscordUserId(ctx.author().getId().asLong()))
						.flatMap(Mono::justOrEmpty)
						.filter(GDLinkedUserData::isLinkActivated)
						.switchIfEmpty(Mono.error(new CommandFailedException("No user specified. If you want to "
								+ "show your own profile, link your Geometry Dash account using `"
								+ ctx.prefixUsed() + "account` and retry this command. Otherwise, you "
								+ "need to specify a user like so: `" + ctx.prefixUsed() + "profile <gd_username>`.")))
						.map(GDLinkedUserData::gdUserId)
						.flatMap(Mono::justOrEmpty)
						.flatMap(ctx.bot().service(GDService.class).getGdClient()::getUserByAccountId))
				.flatMap(user -> GDUsers.makeIconSet(ctx, ctx.bot(), user, ctx.bot().service(GDService.class).getSpriteFactory(), ctx.bot().service(GDService.class).getIconsCache(), ctx.bot().service(GDService.class).getIconChannelId())
						.onErrorResume(e -> Mono.just(e.getMessage()))
						.flatMap(icons -> GDUsers.userProfileView(ctx, ctx.bot(), ctx.author(), user,
										ctx.translate("GDStrings", "user_profile"), "https://i.imgur.com/ppg4HqJ.png", icons)
								.map(MessageSpecTemplate::toMessageCreateSpec)
								.flatMap(ctx::reply)))
				.then();
	}
}
