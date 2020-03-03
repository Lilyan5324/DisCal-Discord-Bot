package org.dreamexposure.discal.server.api.endpoints.v2.announcement;

import org.dreamexposure.discal.core.database.DatabaseManager;
import org.dreamexposure.discal.core.logger.Logger;
import org.dreamexposure.discal.core.object.web.AuthenticationState;
import org.dreamexposure.discal.core.utils.JsonUtils;
import org.dreamexposure.discal.server.utils.Authentication;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import discord4j.core.object.util.Snowflake;

@RestController
@RequestMapping("/v2/announcement")
public class DeleteEndpoint {
	@PostMapping(value = "/delete", produces = "application/json")
	public String deleteAnnouncement(HttpServletRequest request, HttpServletResponse response, @RequestBody String requestBody) {
		//Authenticate...
		AuthenticationState authState = Authentication.authenticate(request);
		if (!authState.isSuccess()) {
			response.setStatus(authState.getStatus());
			response.setContentType("application/json");
			return authState.toJson();
		} else if (authState.isReadOnly()) {
			response.setStatus(401);
			response.setContentType("application/json");
			return JsonUtils.getJsonResponseMessage("Read-Only key not Allowed");
		}

		//Okay, now handle actual request.
		try {
			JSONObject body = new JSONObject(requestBody);
			Snowflake guildId = Snowflake.of(body.getLong("guild_id"));
			UUID announcementId = UUID.fromString(body.getString("announcement_id"));

			if (DatabaseManager.getManager().getAnnouncement(announcementId, guildId) != null) {
				if (DatabaseManager.getManager().deleteAnnouncement(announcementId.toString())) {
					response.setContentType("application/json");
					response.setStatus(200);
					return JsonUtils.getJsonResponseMessage("Announcement successfully deleted");
				}
			} else {
				response.setContentType("application/json");
				response.setStatus(404);
				return JsonUtils.getJsonResponseMessage("Announcement not Found");
			}

			response.setContentType("application/json");
			response.setStatus(500);
			return JsonUtils.getJsonResponseMessage("Internal Server Error");
		} catch (JSONException e) {
			e.printStackTrace();

			response.setContentType("application/json");
			response.setStatus(400);
			return JsonUtils.getJsonResponseMessage("Bad Request");
		} catch (Exception e) {
			Logger.getLogger().exception(null, "[API-v2] Internal delete announcement error", e, true, this.getClass());

			response.setContentType("application/json");
			response.setStatus(500);
			return JsonUtils.getJsonResponseMessage("Internal Server Error");
		}
	}
}
