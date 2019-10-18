package org.dreamexposure.discal.web.network.discord;

import org.dreamexposure.discal.core.enums.GoodTimezone;
import org.dreamexposure.discal.core.enums.announcement.AnnouncementType;
import org.dreamexposure.discal.core.enums.event.EventColor;
import org.dreamexposure.discal.core.logger.Logger;
import org.dreamexposure.discal.core.object.BotSettings;
import org.dreamexposure.discal.core.object.network.discal.ConnectedClient;
import org.dreamexposure.discal.core.object.web.WebGuild;
import org.dreamexposure.discal.core.utils.GlobalConst;
import org.dreamexposure.discal.web.DisCalWeb;
import org.dreamexposure.discal.web.handler.DashboardHandler;
import org.dreamexposure.discal.web.handler.DiscordAccountHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@RestController
public class DiscordLoginHandler {

	@GetMapping("/account/login")
	public static String handleDiscordCode(HttpServletRequest req, HttpServletResponse res, @RequestParam(value = "code") String code) throws IOException {
		OkHttpClient client = new OkHttpClient();

		try {
			RequestBody body = new FormBody.Builder()
				.addEncoded("client_id", BotSettings.ID.get())
				.addEncoded("client_secret", BotSettings.SECRET.get())
				.addEncoded("grant_type", "authorization_code")
				.addEncoded("code", code)
				.addEncoded("redirect_uri", BotSettings.REDIR_URL.get())
				.build();

			okhttp3.Request httpRequest = new okhttp3.Request.Builder()
				.url("https://discordapp.com/api/v6/oauth2/token")
				.post(body)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.build();

			//POST request to discord for access...
			okhttp3.Response httpResponse = client.newCall(httpRequest).execute();

			@SuppressWarnings("ConstantConditions")
			JSONObject info = new JSONObject(httpResponse.body().string());

			if (info.has("access_token")) {
				//GET request for user info...
				Request userDataRequest = new Request.Builder()
					.url("https://discordapp.com/api/v6/users/@me")
					.header("Authorization", "Bearer " + info.getString("access_token"))
					.build();

				Response userDataResponse = client.newCall(userDataRequest).execute();

				Request userGuildsRequest = new Request.Builder()
					.url("https://discordapp.com/api/v6/users/@me/guilds")
					.header("Authorization", "Bearer " + info.getString("access_token"))
					.build();

				Response userGuildsResponse = client.newCall(userGuildsRequest).execute();

				JSONObject userInfo = new JSONObject(userDataResponse.body().string());
				JSONArray jGuilds = new JSONArray(userGuildsResponse.body().string());

				//Get list of guild IDs.
				JSONArray servers = new JSONArray();
				for (int i = 0; i < jGuilds.length(); i++) {
					servers.put(jGuilds.getJSONObject(i).getLong("id"));
				}

				//Saving session info and access info to memory until moved into the database...
				Map<String, Object> m = new HashMap<>();
				m.put("loggedIn", true);
				m.put("client", BotSettings.ID.get());
				m.put("year", LocalDate.now().getYear());
				m.put("redirUri", BotSettings.REDIR_URI.get());
				m.put("inviteUrl", BotSettings.INVITE_URL.get());

				m.put("id", userInfo.getString("id"));
				m.put("username", userInfo.getString("username"));
				m.put("discrim", userInfo.getString("discriminator"));


				List<WebGuild> guilds = new ArrayList<>();
				try {
					OkHttpClient clientWithTimeout = new OkHttpClient.Builder()
						.connectTimeout(1, TimeUnit.SECONDS)
						.build();

					JSONObject comBody = new JSONObject();
					comBody.put("member_id", Long.valueOf(m.get("id") + ""));
					comBody.put("guilds", servers);

					RequestBody httpRequestBody = RequestBody.create(GlobalConst.JSON, comBody.toString());

					//TODO: Fix this shit. Really just remove it. Mirror what we did with Remx
					for (ConnectedClient cc : DisCalWeb.getNetworkInfo().getClients()) {

						try {
							Request requestNew = new Request.Builder()
								.url("https://" + BotSettings.COM_SUB_DOMAIN.get() + cc.getClientIndex() + ".discalbot.com/api/v1/com/website/dashboard/defaults")
								.post(httpRequestBody)
								.header("Content-Type", "application/json")
								.header("Authorization", Credentials.basic(BotSettings.COM_USER.get(), BotSettings.COM_PASS.get()))
								.build();

							Response responseNew = clientWithTimeout.newCall(requestNew).execute();


							if (responseNew.code() == 200) {
								JSONObject responseBody = new JSONObject(responseNew.body().string());

								JSONArray guildsData = responseBody.getJSONArray("guilds");
								for (int i = 0; i < guildsData.length(); i++) {
									guilds.add(new WebGuild().fromJson(guildsData.getJSONObject(i)));
								}
							} else if (responseNew.code() >= 500) {
								//Client must be down... lets remove it...
								DisCalWeb.getNetworkInfo().removeClient(cc.getClientIndex());
							}
						} catch (Exception e) {
							Logger.getLogger().exception(null, "Client response error", e, true, DiscordAccountHandler.class);
							//Remove client to be on the safe side. If client is still up, it'll be re-added on the next keepalive
							DisCalWeb.getNetworkInfo().removeClient(cc.getClientIndex());

						}
					}
				} catch (Exception e) {
					Logger.getLogger().exception(null, "Failed to handle dashboard guild get", e, true, DashboardHandler.class);
				}


				m.put("guilds", guilds);

				m.put("goodTz", GoodTimezone.values());
				m.put("anTypes", AnnouncementType.values());
				m.put("eventColors", EventColor.values());

				String newSessionId = UUID.randomUUID().toString();

				req.getSession(true).setAttribute("account", newSessionId);

				DiscordAccountHandler.getHandler().addAccount(m, req);

				//Finally redirect to the dashboard seamlessly.
				res.sendRedirect("/dashboard");
				return "redirect:/dashboard";
			} else {
				//Token not provided. Authentication denied or errored... Redirect to dashboard so user knows auth failed.
				res.sendRedirect("/dashboard");
				return "redirect:/dashboard";
			}
		} catch (JSONException e) {
			Logger.getLogger().exception(null, "[WEB] JSON || Discord login failed!", e, true, DiscordLoginHandler.class);
			res.sendRedirect("/dashboard");
			return "redirect:/dashboard";
		} catch (Exception e) {
			Logger.getLogger().exception(null, "[WEB] Discord login failed!", e, true, DiscordLoginHandler.class);
			res.sendRedirect("/dashboard");
			return "redirect:/dashboard";
		}
	}

	@GetMapping("/account/logout")
	public static String handleLogout(HttpServletRequest request, HttpServletResponse res) throws IOException {
		try {
			DiscordAccountHandler.getHandler().removeAccount(request);
			request.getSession().invalidate();

			res.sendRedirect("/");
			return "redirect:/";
		} catch (Exception e) {
			Logger.getLogger().exception(null, "[WEB] Discord logout failed!", e, true, DiscordLoginHandler.class);
			res.sendRedirect("/");
			return "redirect:/";
		}
	}
}