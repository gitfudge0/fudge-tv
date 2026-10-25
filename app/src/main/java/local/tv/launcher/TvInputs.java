package local.tv.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Public TV input metadata only: device names depend on what the TV exposes to apps. */
public final class TvInputs {
  private TvInputs() {}

  public static final class Entry {
    public final String id, label, portLabel, status;
    public final int state;

    Entry(String id, String label, String portLabel, int state) {
      this.id = id;
      this.label = label;
      this.portLabel = portLabel;
      this.state = state;
      this.status =
          switch (state) {
            case TvInputManager.INPUT_STATE_CONNECTED -> "Connected";
            case TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "Standby";
            case TvInputManager.INPUT_STATE_DISCONNECTED -> "Disconnected";
            default -> "Status unavailable";
          };
    }
  }

  // Framework-free snapshot keeps grouping deterministic and independently testable.
  static final class Input {
    final String id, parent, label;
    final int state;

    Input(String id, String parent, String label, int state) {
      this.id = id;
      this.parent = parent;
      this.label = label;
      this.state = state;
    }
  }

  static List<Entry> map(List<Input> inputs) {
    Set<String> representedParents = new HashSet<>();
    for (Input input : inputs) {
      if (input.parent != null && !input.parent.isEmpty() && !blank(input.label)) {
        representedParents.add(input.parent);
      }
    }
    List<Entry> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Input input : inputs) {
      if (blank(input.id) || representedParents.contains(input.id) || !seen.add(input.id)) continue;
      String port = "";
      if (!blank(input.parent)) {
        for (Input parent : inputs) {
          if (input.parent.equals(parent.id) && !blank(parent.label)) {
            port = parent.label;
            break;
          }
        }
      }
      // An unnamed logical child provides no more information than its visible port.
      if (blank(input.label) && !blank(port)) continue;
      String label = blank(input.label) ? "HDMI input" : input.label;
      if (label.equals(port)) port = "";
      result.add(new Entry(input.id, label, port, input.state));
    }
    return Collections.unmodifiableList(result);
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

  public static List<Entry> load(Context context) {
    TvInputManager manager;
    List<TvInputInfo> infos;
    try {
      manager = context.getSystemService(TvInputManager.class);
      if (manager == null) return Collections.emptyList();
      infos = manager.getTvInputList();
    } catch (RuntimeException denied) {
      return Collections.emptyList();
    }
    List<Input> inputs = new ArrayList<>();
    for (TvInputInfo info : infos) {
      try {
        if (info.isHidden(context) || !info.isPassthroughInput()) continue;
        String id = info.getId();
        CharSequence label = null;
        try {
          label = info.loadCustomLabel(context);
        } catch (RuntimeException ignored) {
        }
        if (label == null || blank(label.toString())) {
          try {
            label = info.loadLabel(context);
          } catch (RuntimeException ignored) {
          }
        }
        int state = -1;
        try {
          state = manager.getInputState(id);
        } catch (RuntimeException ignored) {
        }
        inputs.add(new Input(id, info.getParentId(), label == null ? "" : label.toString(), state));
      } catch (RuntimeException malformed) {
        // One broken OEM entry must not hide the remaining ports.
      }
    }
    return map(inputs);
  }

  public static Intent viewIntent(String inputId) {
    if (blank(inputId)) throw new IllegalArgumentException("An input ID is required");
    return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
  }

  /** Returns an explicit OEM system home destination without changing default-home settings. */
  public static Intent systemHomeIntent(Context context) {
    Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    try {
      for (ResolveInfo resolved : context.getPackageManager().queryIntentActivities(query, 0)) {
        ActivityInfo activity = resolved.activityInfo;
        if (activity == null
            || !activity.exported
            || !activity.enabled
            || context.getPackageName().equals(activity.packageName)
            || activity.applicationInfo == null
            || !activity.applicationInfo.enabled
            || (activity.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM)
                == 0
            || (activity.permission != null
                && context.checkSelfPermission(activity.permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)) continue;
        return new Intent(query).setClassName(activity.packageName, activity.name);
      }
    } catch (RuntimeException unavailable) {
      // Package visibility and OEM restrictions may leave no accessible destination.
    }
    return null;
  }
}
