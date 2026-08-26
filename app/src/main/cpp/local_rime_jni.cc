#include <jni.h>

#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

#include <rime_api.h>

namespace {

std::mutex g_mutex;
RimeApi* g_api = nullptr;
RimeSessionId g_session = 0;
bool g_started = false;

std::string jstring_to_string(JNIEnv* env, jstring value) {
  if (!value) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) return {};
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

jobjectArray make_strings(JNIEnv* env, const std::vector<std::string>& values) {
  jclass string_class = env->FindClass("java/lang/String");
  if (!string_class) return nullptr;
  jobjectArray result = env->NewObjectArray(
      static_cast<jsize>(values.size()), string_class, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
    jstring value = env->NewStringUTF(values[static_cast<size_t>(i)].c_str());
    env->SetObjectArrayElement(result, i, value);
    env->DeleteLocalRef(value);
  }
  env->DeleteLocalRef(string_class);
  return result;
}

std::string take_commit() {
  if (!g_api || !g_session) return {};
  RIME_STRUCT(RimeCommit, commit);
  if (!g_api->get_commit(g_session, &commit)) return {};
  std::string result = commit.text ? commit.text : "";
  g_api->free_commit(&commit);
  return result;
}

std::vector<std::string> snapshot_locked() {
  std::vector<std::string> values;
  if (!g_api || !g_session) return values;

  const char* input = g_api->get_input(g_session);
  values.emplace_back(input ? input : "");

  RIME_STRUCT(RimeContext, context);
  if (!g_api->get_context(g_session, &context)) return values;

  values.emplace_back(context.composition.preedit ?
                          context.composition.preedit : "");
  for (int i = 0; i < context.menu.num_candidates; ++i) {
    const char* text = context.menu.candidates[i].text;
    if (text && *text) values.emplace_back(text);
  }
  g_api->free_context(&context);
  return values;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_llc_slacker_openime_RimeNative_nativeStartup(
    JNIEnv* env, jclass, jstring shared_dir, jstring user_dir) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_started) return;

  const std::string shared = jstring_to_string(env, shared_dir);
  const std::string user = jstring_to_string(env, user_dir);
  setenv("RIME_SHARED_DATA_DIR", shared.c_str(), 1);
  setenv("RIME_USER_DATA_DIR", user.c_str(), 1);

  g_api = rime_get_api();
  if (!g_api) return;

  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.distribution_name = "openIME";
  traits.distribution_code_name = "openime";
  traits.distribution_version = "1.0";
  traits.app_name = "rime.openime";
  traits.log_dir = "";
  g_api->setup(&traits);
  g_api->initialize(&traits);

  // Deployment is performed off the Android main thread by RimeEngine. Wait
  // here so the first keyboard session never races schema generation.
  if (g_api->start_maintenance) {
    // False makes Rime check the data signature and skip a full rebuild on
    // every service restart. The first install still deploys all schemas.
    g_api->start_maintenance(False);
    if (g_api->join_maintenance_thread) g_api->join_maintenance_thread();
  }

  g_session = g_api->create_session();
  if (g_session && g_api->select_schema) {
    if (!g_api->select_schema(g_session, "luna_pinyin_simp")) {
      g_api->select_schema(g_session, "luna_pinyin");
    }
  }
  g_started = g_session != 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_llc_slacker_openime_RimeNative_nativeSetInput(
    JNIEnv* env, jclass, jstring input) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_api || !g_session || !g_api->set_input) return make_strings(env, {});
  const std::string value = jstring_to_string(env, input);
  g_api->set_input(g_session, value.c_str());
  return make_strings(env, snapshot_locked());
}

extern "C" JNIEXPORT jstring JNICALL
Java_llc_slacker_openime_RimeNative_nativeSelectCandidate(
    JNIEnv* env, jclass, jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_api || !g_session || index < 0) return env->NewStringUTF("");
  if (!g_api->select_candidate(g_session, static_cast<size_t>(index))) {
    return env->NewStringUTF("");
  }
  const std::string commit = take_commit();
  return env->NewStringUTF(commit.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_llc_slacker_openime_RimeNative_nativeCommitFirst(
    JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_api || !g_session) return env->NewStringUTF("");
  if (g_api->select_candidate(g_session, 0)) {
    const std::string commit = take_commit();
    return env->NewStringUTF(commit.c_str());
  }
  if (g_api->commit_composition(g_session)) {
    const std::string commit = take_commit();
    return env->NewStringUTF(commit.c_str());
  }
  return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_llc_slacker_openime_RimeNative_nativeClear(
    JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_api && g_session) g_api->clear_composition(g_session);
}

extern "C" JNIEXPORT void JNICALL
Java_llc_slacker_openime_RimeNative_nativeShutdown(
    JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_api && g_session) g_api->destroy_session(g_session);
  g_session = 0;
  if (g_api && g_started) g_api->finalize();
  g_api = nullptr;
  g_started = false;
}
