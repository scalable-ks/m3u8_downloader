import React, { useEffect, useMemo, useState } from "react";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { StatusBar, StyleSheet } from "react-native";
import { DownloadScreen } from "./src/ui/DownloadScreen.tsx";
import { DownloadManager } from "./src/bridge/downloadManager.ts";
import { MockBridge } from "./src/bridge/mockBridge.ts";
import { pickDirectory } from "./src/bridge/saf.ts";

function App(): JSX.Element {
  const bridge = useMemo(() => new MockBridge(), []);
  const manager = useMemo(() => new DownloadManager(bridge), [bridge]);
  const [selectedFolder, setSelectedFolder] = useState<string | null>(null);

  useEffect(() => {
    bridge.setProgressEmitter((status) => manager.handleProgress(status));
    bridge.setErrorEmitter((error) => manager.handleError(error));
  }, [bridge, manager]);

  const handleStart = async (playlistUri: string) => {
    if (!playlistUri) {
      return;
    }
    await manager.start({
      id: `job-${Date.now()}`,
      masterPlaylistUri: playlistUri,
    });
  };

  const handlePickFolder = async () => {
    const uri = await pickDirectory();
    if (uri) {
      setSelectedFolder(uri);
    }
  };

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView style={styles.container}>
        <DownloadScreen
          manager={manager}
          onStart={handleStart}
          onPickFolder={handlePickFolder}
          selectedFolder={selectedFolder}
        />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#bfbfbf",
  },
});

export default App;
