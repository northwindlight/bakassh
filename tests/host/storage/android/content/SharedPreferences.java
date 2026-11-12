package android.content;

public interface SharedPreferences {
    String getString(String key, String fallback);
    int getInt(String key, int fallback);
    float getFloat(String key, float fallback);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor putFloat(String key, float value);
        Editor remove(String key);
        boolean commit();
        void apply();
    }
}
