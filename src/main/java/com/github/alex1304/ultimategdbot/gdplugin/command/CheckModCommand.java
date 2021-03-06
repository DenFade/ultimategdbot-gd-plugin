package com.github.alex1304.ultimategdbot.gdplugin.command;

import com.github.alex1304.jdash.entity.GDUser;
import com.github.alex1304.jdash.entity.Role;
import com.github.alex1304.ultimategdbot.api.command.CommandFailedException;
import com.github.alex1304.ultimategdbot.api.command.Context;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandAction;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandDescriptor;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandDoc;
import com.github.alex1304.ultimategdbot.api.database.DatabaseService;
import com.github.alex1304.ultimategdbot.api.emoji.EmojiService;
import com.github.alex1304.ultimategdbot.gdplugin.GDService;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLinkedUserDao;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLinkedUserData;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDModDao;
import com.github.alex1304.ultimategdbot.gdplugin.database.ImmutableGDModData;
import com.github.alex1304.ultimategdbot.gdplugin.gdevent.UserDemotedFromElderEvent;
import com.github.alex1304.ultimategdbot.gdplugin.gdevent.UserDemotedFromModEvent;
import com.github.alex1304.ultimategdbot.gdplugin.gdevent.UserPromotedToElderEvent;
import com.github.alex1304.ultimategdbot.gdplugin.gdevent.UserPromotedToModEvent;
import com.github.alex1304.ultimategdbot.gdplugin.util.GDUsers;

import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

@CommandDescriptor(
		aliases = "checkmod",
		shortDescription = "tr:GDStrings/checkmod_desc"
)
public class CheckModCommand {

	@CommandAction
	@CommandDoc("tr:GDStrings/checkmod_run")
	public Mono<Void> run(Context ctx, @Nullable GDUser gdUser) {
		return Mono.justOrEmpty(gdUser)
				.switchIfEmpty(ctx.bot().service(DatabaseService.class)
						.withExtension(GDLinkedUserDao.class, dao -> dao.getByDiscordUserId(ctx.author().getId().asLong()))
						.flatMap(Mono::justOrEmpty)
						.filter(GDLinkedUserData::isLinkActivated)
						.switchIfEmpty(Mono.error(new CommandFailedException(
								ctx.translate("GDStrings", "error_user_not_specified", ctx.prefixUsed(), "checkmod"))))
						.map(GDLinkedUserData::gdUserId)
						.flatMap(ctx.bot().service(GDService.class).getGdClient()::getUserByAccountId))
				.flatMap(user -> Mono.zip(
								ctx.bot().service(EmojiService.class).emoji("success"),
								ctx.bot().service(EmojiService.class).emoji("failed"),
								ctx.bot().service(EmojiService.class).emoji("mod"))
						.flatMap(emojis -> ctx.reply(ctx.translate("GDStrings", "checking_mod", user.getName()) + "\n||"
								+ (user.getRole() == Role.USER
								? emojis.getT2() + ' ' + ctx.translate("GDStrings", "checkmod_failed")
								: emojis.getT1() + ' ' + ctx.translate("GDStrings", "checkmod_success", user.getRole().toString()))+ "||"))
						.then(GDUsers.makeIconSet(ctx, ctx.bot(), user, ctx.bot().service(GDService.class).getSpriteFactory(), ctx.bot().service(GDService.class).getIconsCache(), ctx.bot().service(GDService.class).getIconChannelId())
								.onErrorResume(e -> Mono.empty()))
						.then(ctx.bot().service(DatabaseService.class).withExtension(GDModDao.class, dao -> dao.get(user.getAccountId())))
						.flatMap(Mono::justOrEmpty)
						.switchIfEmpty(Mono.defer(() -> {
							if (user.getRole() == Role.USER) {
								return Mono.empty();
							}
							var isElder = user.getRole() == Role.ELDER_MODERATOR;
							ctx.bot().service(GDService.class).getGdEventDispatcher().dispatch(isElder ? new UserPromotedToElderEvent(user)
									: new UserPromotedToModEvent(user));
							var gdMod = ImmutableGDModData.builder()
									.accountId(user.getAccountId())
									.name(user.getName())
									.isElder(isElder)
									.build();
							return ctx.bot().service(DatabaseService.class)
									.useExtension(GDModDao.class, dao -> dao.insert(gdMod))
									.then(Mono.empty());
						}))
						.flatMap(gdMod -> {
							if (user.getRole() == Role.USER) {
								ctx.bot().service(GDService.class).getGdEventDispatcher().dispatch(gdMod.isElder() 
										? new UserDemotedFromElderEvent(user) : new UserDemotedFromModEvent(user));
								return ctx.bot().service(DatabaseService.class)
										.useExtension(GDModDao.class, dao -> dao.delete(gdMod.accountId()));
							} else {
								var updatedGdMod = ImmutableGDModData.builder().from(gdMod);
								if (user.getRole() == Role.MODERATOR && gdMod.isElder()) {
									ctx.bot().service(GDService.class).getGdEventDispatcher().dispatch(new UserDemotedFromElderEvent(user));
									updatedGdMod.isElder(false);
								} else if (user.getRole() == Role.ELDER_MODERATOR && !gdMod.isElder()) {
									ctx.bot().service(GDService.class).getGdEventDispatcher().dispatch(new UserPromotedToElderEvent(user));
									updatedGdMod.isElder(true);
								}
								updatedGdMod.name(user.getName());
								return ctx.bot().service(DatabaseService.class)
										.useExtension(GDModDao.class, dao -> dao.update(updatedGdMod.build()));
							}
						}))
				.then();
	}
}
