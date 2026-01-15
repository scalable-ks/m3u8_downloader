import React, { useEffect, useMemo, useRef, useState } from "react";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { StatusBar, StyleSheet } from "react-native";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { DownloadScreen } from "./src/ui/DownloadScreen.tsx";
import { DownloadManager } from "./src/bridge/downloadManager.ts";
import { pickDirectory } from "./src/bridge/saf.ts";
import type { Logger } from "./src/bridge/logger.ts";
import { NativeDownloaderBridge } from "./src/bridge/nativeBridge.ts";
import { NativeJobStore } from "./src/bridge/nativeJobStore.ts";
import { parseCookiesInput, parseHeadersInput } from "./src/ui/auth.ts";
import { validatePlaylistUrl } from "./src/ui/validation.ts";

const SELECTED_FOLDER_KEY = "@HlsDownloader:selectedFolder";

function App(): JSX.Element {
  const [logs, setLogs] = useState<string[]>([]);
  const [manager, setManager] = useState<DownloadManager | null>(null);
  const managerRef = useRef<DownloadManager | null>(null);
  const logger = useMemo<Logger>(
    () => ({
      info(message, context) {
        setLogs((current) => [`INFO ${message}`, ...current].slice(0, 16));
      },
      warn(message, context) {
        setLogs((current) => [`WARN ${message}`, ...current].slice(0, 16));
      },
      error(message, context) {
        setLogs((current) => [`ERROR ${message}`, ...current].slice(0, 16));
      },
    }),
    [],
  );
  const [selectedFolder, setSelectedFolder] = useState<string | null>(null);

  useEffect(() => {
    const bridge = new NativeDownloaderBridge(
      (status) => managerRef.current?.handleProgress(status),
      (error) => managerRef.current?.handleError(error),
    );
    const store = new NativeJobStore(() => bridge.listJobs());
    const managerInstance = new DownloadManager(bridge, store, logger);
    managerRef.current = managerInstance;
    setManager(managerInstance);
  }, [logger]);

  useEffect(() => {
    const loadSavedFolder = async () => {
      try {
        const savedFolder = await AsyncStorage.getItem(SELECTED_FOLDER_KEY);
        if (savedFolder) {
          setSelectedFolder(savedFolder);
          logger.info("Loaded saved folder", { folder: savedFolder });
        }
      } catch (error) {
        logger.error("Failed to load saved folder", { error });
      }
    };
    loadSavedFolder();
  }, [logger]);

  const handleStart = async (playlistUri: string, headersInput: string, cookiesInput: string) => {
    if (!manager) {
      return;
    }
    logger.info("start pressed");
    if (!playlistUri) {
      manager.handleError({
        id: "ui",
        code: "validation",
        message: "Enter a playlist URL.",
      });
      return;
    }
    if (!validatePlaylistUrl(playlistUri)) {
      manager.handleError({
        id: "ui",
        code: "validation",
        message: "Invalid URL. Must be http or https.",
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
    let headers: Record<string, string> | undefined;
    let cookies;
    try {
      headers = parseHeadersInput(headersInput);
      cookies = parseCookiesInput(cookiesInput);
    } catch (error) {
      manager.handleError({
        id: "ui",
        code: "validation",
        message: error instanceof Error ? error.message : "Invalid auth input.",
      });
      return;
    }
    logger.info("start job", { playlistUri, exportTreeUri: selectedFolder });
    try {
      await manager.startPlanned({
        id: `job-${Date.now()}`,
        masterPlaylistUri: playlistUri,
        exportTreeUri: selectedFolder,
        headers,
        cookies,
      });
    } catch (error) {
      logger.error("Failed to start download", { error });
      manager.handleError({
        id: "ui",
        code: "start_failed",
        message: error instanceof Error ? error.message : "Failed to start download",
      });
    }
  };

  const handlePickFolder = async () => {
    logger.info("pick folder pressed");
    const uri = await pickDirectory();
    if (uri) {
      logger.info("folder selected", { uri });
      setSelectedFolder(uri);
      try {
        await AsyncStorage.setItem(SELECTED_FOLDER_KEY, uri);
        logger.info("Saved folder to storage", { uri });
      } catch (error) {
        logger.error("Failed to save folder to storage", { error });
      }
    }
  };

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView style={styles.container}>
        {manager ? (
          <DownloadScreen
            manager={manager}
            onStart={handleStart}
            onPickFolder={handlePickFolder}
            selectedFolder={selectedFolder}
            logs={logs}
          />
        ) : null}
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
