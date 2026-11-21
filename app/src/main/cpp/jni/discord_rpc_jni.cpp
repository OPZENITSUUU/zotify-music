#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#include <jni.h>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>

namespace {
std::mutex gMutex;
std::shared_ptr<discordpp::Client> gClient;

std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

uint64_t nowMillis() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count());
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_data_discord_DiscordSocialPresence_nativeInit(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gClient) return JNI_TRUE;
    try {
        gClient = std::make_shared<discordpp::Client>();
        gClient->SetApplicationId(1573592442916307075ULL);
        return JNI_TRUE;
    } catch (...) {
        gClient.reset();
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_data_discord_DiscordSocialPresence_nativeUpdate(
    JNIEnv* env, jobject, jstring titleValue, jstring artistValue,
    jstring artworkValue, jlong positionMs, jlong durationMs,
    jboolean spotifyStyle, jboolean playing) {
    const std::string title = fromJava(env, titleValue);
    const std::string artist = fromJava(env, artistValue);
    const std::string artwork = fromJava(env, artworkValue);
    if (title.empty()) return;

    std::shared_ptr<discordpp::Client> client;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        client = gClient;
    }
    if (!client) return;

    discordpp::Activity activity;
    activity.SetType(discordpp::ActivityTypes::Listening);
    activity.SetName(spotifyStyle == JNI_TRUE ? "Spotify" : "Zotify Music");
    activity.SetDetails(title);
    if (!artist.empty()) activity.SetState(artist);
    activity.SetSupportedPlatforms(discordpp::ActivityGamePlatforms::Android);

    if (playing == JNI_TRUE) {
        const uint64_t now = nowMillis();
        const uint64_t position = static_cast<uint64_t>(positionMs > 0 ? positionMs : 0);
        discordpp::ActivityTimestamps timestamps;
        timestamps.SetStart(now > position ? now - position : now);
        if (durationMs > 0) {
            timestamps.SetEnd(now + static_cast<uint64_t>(durationMs > positionMs
                ? durationMs - positionMs : 0));
        }
        activity.SetTimestamps(timestamps);
    }

    if (!artwork.empty()) {
        discordpp::ActivityAssets assets;
        assets.SetLargeImage(std::optional<std::string>(artwork));
        assets.SetLargeText(title);
        activity.SetAssets(assets);
    }

    client->UpdateRichPresence(std::move(activity),
        [](discordpp::ClientResult result) {
            if (!result.Successful()) {
                // Discord may not be installed/signed in or the app may not be
                // enabled for Social SDK. The caller keeps playback unaffected.
            }
        });
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_data_discord_DiscordSocialPresence_nativeClear(
    JNIEnv*, jobject) {
    std::shared_ptr<discordpp::Client> client;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        client = gClient;
    }
    if (client) client->ClearRichPresence();
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_data_discord_DiscordSocialPresence_nativeRunCallbacks(
    JNIEnv*, jobject) {
    discordpp::RunCallbacks();
}
