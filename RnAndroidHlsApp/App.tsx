import React, { useEffect, useMemo, useState } from "react";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { StatusBar, StyleSheet } from "react-native";
import { DownloadScreen } from "./src/ui/DownloadScreen.tsx";
import { DownloadManager } from "./src/bridge/downloadManager.ts";
import { MockBridge } from "./src/bridge/mockBridge.ts";
import { pickDirectory } from "./src/bridge/saf.ts";
import type { Logger } from "./src/bridge/logger.ts";

function App(): JSX.Element {
  const bridge = useMemo(() => new MockBridge(), []);
  const [logs, setLogs] = useState<string[]>([]);
  const logger = useMemo<Logger>(
    () => ({
      info(message, context) {
        setLogs((current) => [`INFO ${message}`, ...current].slice(0, 12));
      },
      warn(message, context) {
        setLogs((current) => [`WARN ${message}`, ...current].slice(0, 12));
      },
      error(message, context) {
        setLogs((current) => [`ERROR ${message}`, ...current].slice(0, 12));
      },
    }),
    [],
  );
  const manager = useMemo(() => new DownloadManager(bridge, undefined, logger), [bridge, logger]);
  const [selectedFolder, setSelectedFolder] = useState<string | null>(null);

  useEffect(() => {
    bridge.setProgressEmitter((status) => manager.handleProgress(status));
    bridge.setErrorEmitter((error) => manager.handleError(error));
  }, [bridge, manager]);

  const handleStart = async (playlistUri: string) => {
    if (!playlistUri) {
      manager.handleError({
        id: "ui",
        code: "validation",
        message: "Enter a playlist URL.",
      });
      return;
    }
    if (!selectedFolder) {
      manager.handleError({
        id: "ui",
        code: "validation",
        message: "Choose a save folder.",
      });
      return;
    }
    const job = await manager.start({
      id: `job-${Date.now()}`,
      masterPlaylistUri: playlistUri,
      exportTreeUri: selectedFolder,
    });
    manager.handleProgress({
      id: job.id,
      state: job.state,
      progress: job.progress,
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
          logs={logs}
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
