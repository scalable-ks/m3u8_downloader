import React, { useEffect, useMemo, useRef, useState } from "react";
import { Animated, FlatList, Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import type { DownloadJob } from "../domain/types.ts";
import type { DownloadManager } from "../bridge/downloadManager.ts";
import { useDownloadJobs } from "./useDownloadJobs.tsx";

interface DownloadScreenProps {
  manager: DownloadManager;
  onStart: (playlistUri: string) => Promise<void>;
  defaultUri?: string;
}

function formatProgress(job: DownloadJob): string {
  const total = job.progress.totalBytes ?? 0;
  const totalText = total > 0 ? ` / ${total}` : "";
  return `${job.progress.bytesDownloaded}${totalText}`;
}

export function DownloadScreen(props: DownloadScreenProps): JSX.Element {
  const { jobs, lastError, pause, resume, cancel } = useDownloadJobs(props.manager);
  const pulse = useRef(new Animated.Value(1)).current;
  const [playlistUri, setPlaylistUri] = useState(props.defaultUri ?? "");

  useEffect(() => {
    const animation = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, {
          toValue: 1.05,
          duration: 700,
          useNativeDriver: true,
        }),
        Animated.timing(pulse, {
          toValue: 1,
          duration: 700,
          useNativeDriver: true,
        }),
      ]),
    );
    animation.start();
    return () => animation.stop();
  }, [pulse]);

  const listData = useMemo(() => jobs, [jobs]);

  return (
    <View style={styles.container}>
      <View style={styles.window}>
        <View style={styles.titleBar}>
          <Text style={styles.titleBarText}>DOWNLOADS.EXE</Text>
          <View style={styles.titleBarBadge} />
        </View>
        <Text style={styles.label}>PLAYLIST URL</Text>
        <TextInput
          value={playlistUri}
          onChangeText={setPlaylistUri}
          placeholder="https://example.com/master.m3u8"
          style={styles.input}
          placeholderTextColor="#6b7280"
          autoCapitalize="none"
          autoCorrect={false}
        />
        {lastError ? <Text style={styles.error}>{lastError.message}</Text> : null}
        <Animated.View style={[styles.startButtonWrapper, { transform: [{ scale: pulse }] }]}>
          <Pressable style={styles.startButton} onPress={() => props.onStart(playlistUri.trim())}>
            <Text style={styles.startButtonText}>START DOWNLOAD</Text>
          </Pressable>
        </Animated.View>
      </View>
      <FlatList
        data={listData}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => (
          <View style={styles.card}>
            <View style={styles.cardHeader}>
              <Text style={styles.jobTitle}>{item.id}</Text>
              <Text style={styles.statusChip}>{item.state.toUpperCase()}</Text>
            </View>
            <Text style={styles.jobMeta}>PROGRESS {formatProgress(item)}</Text>
            <View style={styles.actions}>
              <Pressable style={styles.action} onPress={() => pause(item.id)}>
                <Text style={styles.actionText}>PAUSE</Text>
              </Pressable>
              <Pressable style={styles.action} onPress={() => resume(item.id)}>
                <Text style={styles.actionText}>RESUME</Text>
              </Pressable>
              <Pressable style={styles.action} onPress={() => cancel(item.id)}>
                <Text style={styles.actionText}>CANCEL</Text>
              </Pressable>
            </View>
          </View>
        )}
        contentContainerStyle={styles.listContent}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 12,
    backgroundColor: "#bfbfbf",
  },
  window: {
    borderWidth: 2,
    borderColor: "#000000",
    backgroundColor: "#cfcfcf",
    padding: 8,
    marginBottom: 12,
    shadowColor: "#000000",
    shadowOpacity: 0.25,
    shadowOffset: { width: 3, height: 3 },
  },
  titleBar: {
    backgroundColor: "#001ea8",
    paddingVertical: 6,
    paddingHorizontal: 8,
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 8,
  },
  titleBarText: {
    color: "#ffffff",
    fontSize: 14,
    fontFamily: "monospace",
    letterSpacing: 1,
  },
  titleBarBadge: {
    width: 12,
    height: 12,
    backgroundColor: "#ffb300",
    borderWidth: 2,
    borderColor: "#ffffff",
  },
  error: {
    color: "#ff005d",
    marginBottom: 8,
    fontFamily: "monospace",
  },
  label: {
    fontFamily: "monospace",
    fontSize: 12,
    color: "#0f172a",
    marginBottom: 4,
  },
  input: {
    backgroundColor: "#fef3c7",
    borderWidth: 2,
    borderTopColor: "#ffffff",
    borderLeftColor: "#ffffff",
    borderRightColor: "#4b4b4b",
    borderBottomColor: "#4b4b4b",
    paddingVertical: 6,
    paddingHorizontal: 8,
    fontFamily: "monospace",
    marginBottom: 10,
  },
  startButtonWrapper: {
    alignSelf: "center",
    marginBottom: 10,
  },
  startButton: {
    backgroundColor: "#ff33cc",
    paddingVertical: 12,
    paddingHorizontal: 20,
    borderWidth: 2,
    borderTopColor: "#ffffff",
    borderLeftColor: "#ffffff",
    borderRightColor: "#4b4b4b",
    borderBottomColor: "#4b4b4b",
    alignItems: "center",
  },
  startButtonText: {
    color: "#ffffff",
    fontWeight: "700",
    fontSize: 16,
    letterSpacing: 1,
    fontFamily: "monospace",
  },
  card: {
    padding: 12,
    backgroundColor: "#d7d7d7",
    borderWidth: 2,
    borderTopColor: "#ffffff",
    borderLeftColor: "#ffffff",
    borderRightColor: "#4b4b4b",
    borderBottomColor: "#4b4b4b",
    marginBottom: 12,
  },
  cardHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 6,
  },
  jobTitle: {
    fontSize: 16,
    fontWeight: "700",
    fontFamily: "monospace",
    letterSpacing: 0.5,
  },
  statusChip: {
    backgroundColor: "#00ffd5",
    paddingVertical: 2,
    paddingHorizontal: 6,
    borderWidth: 2,
    borderTopColor: "#ffffff",
    borderLeftColor: "#ffffff",
    borderRightColor: "#4b4b4b",
    borderBottomColor: "#4b4b4b",
    fontFamily: "monospace",
    fontSize: 10,
  },
  jobMeta: {
    color: "#1f2937",
    fontFamily: "monospace",
    marginBottom: 6,
  },
  actions: {
    flexDirection: "row",
    gap: 8,
  },
  action: {
    paddingVertical: 6,
    paddingHorizontal: 10,
    backgroundColor: "#ffd400",
    borderWidth: 2,
    borderTopColor: "#ffffff",
    borderLeftColor: "#ffffff",
    borderRightColor: "#4b4b4b",
    borderBottomColor: "#4b4b4b",
  },
  actionText: {
    fontFamily: "monospace",
    fontWeight: "700",
  },
  listContent: {
    paddingBottom: 16,
  },
});
