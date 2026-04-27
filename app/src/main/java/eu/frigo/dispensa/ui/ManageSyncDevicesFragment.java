package eu.frigo.dispensa.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.Set;

import eu.frigo.dispensa.R;
import eu.frigo.dispensa.sync.SyncPermissionManager;

/**
 * Fragment that lets the user manage which local-network devices are trusted for sync.
 *
 * <p>Shows two sections:
 * <ul>
 *   <li><b>Trusted devices</b> — devices the user has approved; each has a "Revoke" button.</li>
 *   <li><b>Pending devices</b> — devices that have attempted to connect but are not yet
 *       approved; each has "Approve" and "Dismiss" buttons.</li>
 * </ul>
 *
 * <p>Device identifiers are UUID strings; they are shown abbreviated to 8 characters for
 * readability (e.g. {@code "a1b2c3d4…"}).
 */
public class ManageSyncDevicesFragment extends Fragment {

    private SyncPermissionManager permissionManager;
    private LinearLayout container;

    public ManageSyncDevicesFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manage_sync_devices, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar_manage_devices);
        toolbar.setTitle(getString(R.string.title_manage_sync_devices));

        container = view.findViewById(R.id.container_sync_devices);
        permissionManager = new SyncPermissionManager(requireContext());

        refresh();
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void refresh() {
        container.removeAllViews();

        Set<String> trusted = permissionManager.getTrustedDeviceIds();
        Set<String> pending = permissionManager.getPendingDeviceIds();

        addSectionHeader(getString(R.string.sync_devices_section_trusted));
        if (trusted.isEmpty()) {
            addInfoText(getString(R.string.sync_devices_trusted_empty));
        } else {
            for (String deviceId : trusted) {
                addTrustedRow(deviceId);
            }
        }

        addSectionHeader(getString(R.string.sync_devices_section_pending));
        if (pending.isEmpty()) {
            addInfoText(getString(R.string.sync_devices_pending_empty));
        } else {
            for (String deviceId : pending) {
                addPendingRow(deviceId);
            }
        }
    }

    private void addSectionHeader(String title) {
        TextView header = new TextView(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dpToPx(16);
        params.bottomMargin = dpToPx(4);
        header.setLayoutParams(params);
        header.setText(title);
        header.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        container.addView(header);
    }

    private void addInfoText(String text) {
        TextView info = new TextView(requireContext());
        info.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        info.setText(text);
        info.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        container.addView(info);
    }

    private void addTrustedRow(String deviceId) {
        LinearLayout row = buildRow();

        TextView label = buildDeviceLabel(deviceId);
        row.addView(label);

        Button revokeBtn = new Button(requireContext());
        revokeBtn.setText(R.string.sync_devices_action_revoke);
        revokeBtn.setOnClickListener(v -> {
            permissionManager.revoke(deviceId);
            refresh();
        });
        row.addView(revokeBtn);

        container.addView(row);
    }

    private void addPendingRow(String deviceId) {
        LinearLayout row = buildRow();

        TextView label = buildDeviceLabel(deviceId);
        row.addView(label);

        Button approveBtn = new Button(requireContext());
        approveBtn.setText(R.string.sync_devices_action_approve);
        approveBtn.setOnClickListener(v -> {
            permissionManager.trust(deviceId);
            refresh();
        });
        row.addView(approveBtn);

        Button dismissBtn = new Button(requireContext());
        dismissBtn.setText(R.string.sync_devices_action_dismiss);
        dismissBtn.setOnClickListener(v -> {
            permissionManager.dismissPending(deviceId);
            refresh();
        });
        row.addView(dismissBtn);

        container.addView(row);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LinearLayout buildRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(4), 0, dpToPx(4));
        return row;
    }

    private TextView buildDeviceLabel(String deviceId) {
        TextView label = new TextView(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(params);
        // Show first 8 chars of UUID for readability
        String shortId = deviceId.length() > 8 ? deviceId.substring(0, 8) + "…" : deviceId;
        label.setText(getString(R.string.sync_devices_device_id_label, shortId));
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        return label;
    }

    private int dpToPx(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
