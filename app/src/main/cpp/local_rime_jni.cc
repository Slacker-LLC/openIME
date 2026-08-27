#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

#include <rime_api.h>

namespace {

std::mutex g_mutex;
RimeApi* g_api = nullptr;
RimeSessionId g_session = 0;
bool g_initialized = false;

constexpr uint32_t kReplacementCharacter = 0xFFFD;

void append_utf8(std::string* out, uint32_t code_point) {
  if (!out) return;
  if (code_point <= 0x7F) {
    out->push_back(static_cast<char>(code_point));
  } else if (code_point <= 0x7FF) {
    out->push_back(static_cast<char>(0xC0 | (code_point >> 6)));
    out->push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
  } else if (code_point <= 0xFFFF) {
    out->push_back(static_cast<char>(0xE0 | (code_point >> 12)));
    out->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
    out->push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
  } else {
    out->push_back(static_cast<char>(0xF0 | (code_point >> 18)));
    out->push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
    out->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
    out->push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
  }
}

std::string jstring_to_utf8(JNIEnv* env, jstring value) {
  if (!value) return {};
  const jsize length = env->GetStringLength(value);
  const jchar* chars = env->GetStringChars(value, nullptr);
  if (!chars) return {};

  std::string result;
  result.reserve(static_cast<size_t>(length) * 3);
  for (jsize i = 0; i < length; ++i) {
    const uint32_t first = chars[i];
    uint32_t code_point = first;
    if (first >= 0xD800 && first <= 0xDBFF) {
      if (i + 1 < length) {
        const uint32_t second = chars[i + 1];
        if (second >= 0xDC00 && second <= 0xDFFF) {
          code_point = 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00);
          ++i;
        } else {
          code_point = kReplacementCharacter;
        }
      } else {
        code_point = kReplacementCharacter;
      }
    } else if (first >= 0xDC00 && first <= 0xDFFF) {
      code_point = kReplacementCharacter;
    }
    append_utf8(&result, code_point);
  }
  env->ReleaseStringChars(value, chars);
  return result;
}

bool is_continuation(uint8_t value) {
  return (value & 0xC0) == 0x80;
}

uint32_t decode_utf8(const std::string& text, size_t* position) {
  if (!position || *position >= text.size()) return kReplacementCharacter;
  const size_t i = *position;
  const uint8_t b0 = static_cast<uint8_t>(text[i]);

  if (b0 <= 0x7F) {
    *position = i + 1;
    return b0;
  }

  if (b0 >= 0xC2 && b0 <= 0xDF && i + 1 < text.size()) {
    const uint8_t b1 = static_cast<uint8_t>(text[i + 1]);
    if (is_continuation(b1)) {
      *position = i + 2;
      return ((b0 & 0x1F) << 6) | (b1 & 0x3F);
    }
  }

  if (b0 >= 0xE0 && b0 <= 0xEF && i + 2 < text.size()) {
    const uint8_t b1 = static_cast<uint8_t>(text[i + 1]);
    const uint8_t b2 = static_cast<uint8_t>(text[i + 2]);
    const bool second_valid =
        is_continuation(b1) &&
        (b0 != 0xE0 || b1 >= 0xA0) &&
        (b0 != 0xED || b1 <= 0x9F);
    if (second_valid && is_continuation(b2)) {
      *position = i + 3;
      return ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
    }
  }

  if (b0 >= 0xF0 && b0 <= 0xF4 && i + 3 < text.size()) {
    const uint8_t b1 = static_cast<uint8_t>(text[i + 1]);
    const uint8_t b2 = static_cast<uint8_t>(text[i + 2]);
    const uint8_t b3 = static_cast<uint8_t>(text[i + 3]);
    const bool second_valid =
        is_continuation(b1) &&
        (b0 != 0xF0 || b1 >= 0x90) &&
        (b0 != 0xF4 || b1 <= 0x8F);
    if (second_valid && is_continuation(b2) && is_continuation(b3)) {
      *position = i + 4;
      return ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) |
             ((b2 & 0x3F) << 6) | (b3 & 0x3F);
    }
  }

  // Invalid or truncated UTF-8: replace one byte and continue so malformed
  // native text can never escape as a pending JNI exception.
  *position = i + 1;
  return kReplacementCharacter;
}

jstring utf8_to_jstring(JNIEnv* env, const std::string& value) {
  std::vector<jchar> utf16;
  utf16.reserve(value.size());
  size_t position = 0;
  while (position < value.size()) {
    uint32_t code_point = decode_utf8(value, &position);
    if (code_point <= 0xFFFF) {
      utf16.push_back(static_cast<jchar>(code_point));
    } else {
      code_point -= 0x10000;
      utf16.push_back(static_cast<jchar>(0xD800 + (code_point >> 10)));
      utf16.push_back(static_cast<jchar>(0xDC00 + (code_point & 0x3FF)));
    }
  }
  const jchar empty = 0;
  return env->NewString(utf16.empty() ? &empty : utf16.data(),
                        static_cast<jsize>(utf16.size()));
}

jobjectArray make_strings(JNIEnv* env, const std::vector<std::string>& values) {
  jclass string_class = env->FindClass("java/lang/String");
  if (!string_class) return nullptr;
  jobjectArray result = env->NewObjectArray(
      static_cast<jsize>(values.size()), string_class, nullptr);
  if (!result) {
    env->DeleteLocalRef(string_class);
    return nullptr;
  }
  for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
    jstring value = utf8_to_jstring(env, values[static_cast<size_t>(i)]);
    if (!value) break;
    env->SetObjectArrayElement(result, i, value);
    env->DeleteLocalRef(value);
    if (env->ExceptionCheck()) break;
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

std::string finish_selection() {
  std::string result = take_commit();
  if (!result.empty()) return result;
  if (g_api && g_session && g_api->commit_composition &&
      g_api->commit_composition(g_session)) {
    return take_commit();
  }
  return {};
}

void append_candidate(std::vector<std::string>* values, const char* text) {
  if (!values || !text || !*text) return;
  // Keep one slot for every native candidate index. Kotlin removes duplicate
  // labels for display, but retains this original index so selecting a word
  // still addresses the exact item in librime's complete candidate list.
  values->emplace_back(text);
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

  // The context menu only exposes the current page (five items in the
  // bundled default). Iterate the complete candidate stream so Android's
  // expandable candidate panel can actually reach uncommon characters and
  // words without changing Rime's ranking.
  bool iterated = false;
  if (g_api->candidate_list_begin && g_api->candidate_list_next &&
      g_api->candidate_list_end) {
    RimeCandidateListIterator iterator = {0};
    if (g_api->candidate_list_begin(g_session, &iterator)) {
      iterated = true;
      while (values.size() < 98 && g_api->candidate_list_next(&iterator)) {
        append_candidate(&values, iterator.candidate.text);
      }
      g_api->candidate_list_end(&iterator);
    }
  }
  if (!iterated) {
    for (int i = 0; i < context.menu.num_candidates; ++i) {
      append_candidate(&values, context.menu.candidates[i].text);
    }
  }
  g_api->free_context(&context);
  return values;
}

bool select_schema_locked(const std::string& schema_id) {
  return g_api && g_session && g_api->select_schema && !schema_id.empty() &&
         g_api->select_schema(g_session, schema_id.c_str());
}

void shutdown_locked() {
  if (g_api && g_session) g_api->destroy_session(g_session);
  g_session = 0;
  // initialize() can succeed even when create_session()/schema selection later
  // fails. Track initialization independently so every partial startup is
  // finalized and a subsequent startup begins from a clean process state.
  if (g_api && g_initialized) g_api->finalize();
  g_api = nullptr;
  g_initialized = false;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_llc_slacker_openime_RimeNative_nativeStartup(
    JNIEnv* env, jclass, jstring shared_dir, jstring user_dir) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_api && g_session) return;
  // Recover from any previous partial initialization before retrying.
  if (g_api || g_initialized) shutdown_locked();

  const std::string shared = jstring_to_utf8(env, shared_dir);
  const std::string user = jstring_to_utf8(env, user_dir);
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
  g_initialized = true;

  // Deployment is performed off the Android main thread by RimeEngine. Wait
  // here so the first keyboard session never races schema generation.
  if (g_api->start_maintenance) {
    // False makes Rime check the data signature and skip a full rebuild on
    // every service restart. The first install still deploys all schemas.
    g_api->start_maintenance(False);
    if (g_api->join_maintenance_thread) g_api->join_maintenance_thread();
  }

  g_session = g_api->create_session();
  if (!g_session) return;

  // A live session is not sufficient: without a selected schema every later
  // candidate query is empty. Require one of the bundled Pinyin schemas.
  const bool schema_selected =
      select_schema_locked("luna_pinyin_simp") ||
      select_schema_locked("luna_pinyin");
  if (!schema_selected) {
    g_api->destroy_session(g_session);
    g_session = 0;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_llc_slacker_openime_RimeNative_nativeSelectSchema(
    JNIEnv* env, jclass, jstring schema_id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  const std::string schema = jstring_to_utf8(env, schema_id);
  return select_schema_locked(schema) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_llc_slacker_openime_RimeNative_nativeSetInput(
    JNIEnv* env, jclass, jstring input) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_api || !g_session || !g_api->set_input) return make_strings(env, {});
  const std::string value = jstring_to_utf8(env, input);
  g_api->set_input(g_session, value.c_str());
  return make_strings(env, snapshot_locked());
}

extern "C" JNIEXPORT jstring JNICALL
Java_llc_slacker_openime_RimeNative_nativeSelectCandidate(
    JNIEnv* env, jclass, jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_api || !g_session || index < 0) return utf8_to_jstring(env, "");
  if (!g_api->select_candidate(g_session, static_cast<size_t>(index))) {
    return utf8_to_jstring(env, "");
  }
  // Selecting a candidate normally only confirms a Rime segment. Explicitly
  // commit the completed composition so the chosen word is learned, any
  // remaining segmented input is preserved, and the Android pre-edit can be
  // cleared atomically after the click.
  return utf8_to_jstring(env, finish_selection());
}

extern "C" JNIEXPORT jstring JNICALL
Java_llc_slacker_openime_RimeNative_nativeCommitFirst(
    JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_api || !g_session) return utf8_to_jstring(env, "");
  if (g_api->select_candidate(g_session, 0)) {
    return utf8_to_jstring(env, finish_selection());
  }
  if (g_api->commit_composition(g_session)) {
    return utf8_to_jstring(env, take_commit());
  }
  return utf8_to_jstring(env, "");
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
  shutdown_locked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_llc_slacker_openime_RimeNative_nativeUtf8RoundTripForTest(
    JNIEnv* env, jclass, jstring input) {
  return utf8_to_jstring(env, jstring_to_utf8(env, input));
}
