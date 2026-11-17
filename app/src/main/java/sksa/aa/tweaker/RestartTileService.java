package sksa.aa.tweaker;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

/** Quick Settings tile: restart Android Auto and bring the head unit server back up. */
@RequiresApi(Build.VERSION_CODES.N)
public class RestartTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    public void onClick() {
        final Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.updateTile();
        }
        new Thread(() -> {
            AndroidAuto.restartForPreview(getApplicationContext());
            Tile t = getQsTile();
            if (t != null) {
                t.setState(Tile.STATE_INACTIVE);
                t.updateTile();
            }
        }).start();
    }
}
