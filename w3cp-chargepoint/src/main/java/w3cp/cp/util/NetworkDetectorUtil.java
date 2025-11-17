package w3cp.cp.util;

import lombok.extern.slf4j.Slf4j;
import w3cp.model.ChargePointStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
public class NetworkDetectorUtil {

  // Drivers commonly used for USB/WWAN modems
  private static final Set<String> WWAN_DRIVERS = Set.of(
      "qmi_wwan", "cdc_mbim", "cdc_ncm", "huawei_cdc_ncm", "rndis_host", "cdc_ether"
  );

  // Interfaces we should ignore
  private static final List<String> IGNORE_PREFIXES = List.of(
      "lo", "veth", "br-", "docker", "virbr", "tun", "tap", "wg", "zt", "tailscale"
  );

  public static ChargePointStatus.ConnectionType detectConnectionType() {
    // 1) Prefer the interface that owns the default route (IPv4 first, then IPv6)
    String iface = Optional.ofNullable(getDefaultRouteIfaceV4())  
        .orElseGet(NetworkDetectorUtil::getDefaultRouteIfaceV6);

    if (iface != null) {
      ChargePointStatus.ConnectionType t = classifyIfUp(iface);
      if (t != ChargePointStatus.ConnectionType.unknown) {
        log.info("✅ Default-route interface '{}' detected as {}", iface, t);
        return t;
      }
    }

    // 2) Fallback: scan all "UP" and non-ignored interfaces, pick a sensible priority
    List<String> candidates = listRealIfaces();
    // Priority: ethernet > wifi > lte (detected), then unknowns
    String eth = candidates.stream().filter(i -> isUp(i) && isEthernet(i)).findFirst().orElse(null);
    if (eth != null) return logAnd(eth, ChargePointStatus.ConnectionType.ethernet);

    String wifi = candidates.stream().filter(i -> isUp(i) && isWireless(i)).findFirst().orElse(null);
    if (wifi != null) return logAnd(wifi, ChargePointStatus.ConnectionType.wifi);

    String lte = candidates.stream().filter(i -> isUp(i) && isWwan(i)).findFirst().orElse(null);
    if (lte != null) return logAnd(lte, ChargePointStatus.ConnectionType.lte);

    // 3) Nothing convincing
    log.info("⚠️ No active uplink detected. Returning 'unknown'.");
    return ChargePointStatus.ConnectionType.unknown;
  }

  /* ---------- helpers ---------- */

  private static ChargePointStatus.ConnectionType logAnd(String iface, ChargePointStatus.ConnectionType t) {
    log.info("✅ Detected active interface '{}', classified as {}", iface, t);
    return t;
  }

  private static boolean isIgnored(String name) {
    return IGNORE_PREFIXES.stream().anyMatch(name::startsWith);
  }

  private static List<String> listRealIfaces() {
    Path sys = Path.of("/sys/class/net");
    if (!Files.isDirectory(sys)) return List.of();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(sys)) {
      List<String> all = new ArrayList<>();
      for (Path p : ds) {
        String n = p.getFileName().toString();
        if (!isIgnored(n)) all.add(n);
      }
      return all;
    } catch (IOException e) {
      log.warn("Failed to list /sys/class/net", e);
      return List.of();
    }
  }

  private static boolean isUp(String iface) {
    try {
      String oper = Files.readString(Path.of("/sys/class/net", iface, "operstate")).trim();
      if (!oper.equalsIgnoreCase("up")) return false;
      Path carrier = Path.of("/sys/class/net", iface, "carrier");
      if (Files.exists(carrier)) {
        String c = Files.readString(carrier).trim();
        return "1".equals(c);
      }
      // Some virtual/wireless paths may not expose carrier; accept operstate=up
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isWireless(String iface) {
    // Linux marks wifi with a 'wireless' dir or a phy80211 symlink
    Path base = Path.of("/sys/class/net", iface);
    return Files.isDirectory(base.resolve("wireless")) ||
        Files.exists(base.resolve("phy80211"));
  }

  private static boolean isWwan(String iface) {
    // Detect via driver name
    String drv = driverName(iface);
    return drv != null && WWAN_DRIVERS.contains(drv);
  }

  private static boolean isEthernet(String iface) {
    // Ethernet if ARPHRD_ETHER and not wireless and not wwan driver
    if (isWireless(iface) || isWwan(iface)) return false;
    try {
      String type = Files.readString(Path.of("/sys/class/net", iface, "type")).trim();
      return "1".equals(type); // ARPHRD_ETHER
    } catch (IOException e) {
      return false;
    }
  }

  private static ChargePointStatus.ConnectionType classifyIfUp(String iface) {
    if (!listRealIfaces().contains(iface) || !isUp(iface)) return ChargePointStatus.ConnectionType.unknown;
    if (isWireless(iface)) return ChargePointStatus.ConnectionType.wifi;
    if (isWwan(iface))     return ChargePointStatus.ConnectionType.lte;
    if (isEthernet(iface)) return ChargePointStatus.ConnectionType.ethernet;
    return ChargePointStatus.ConnectionType.unknown;
  }

  private static String driverName(String iface) {
    try {
      // /sys/class/net/<iface>/device/driver -> .../<driver>
      Path link = Path.of("/sys/class/net", iface, "device", "driver");
      if (Files.isSymbolicLink(link)) {
        Path target = Files.readSymbolicLink(link);
        String name = target.getFileName().toString();
        log.debug("iface {} driver {}", iface, name);
        return name;
      }
    } catch (IOException ignored) {}
    return null;
  }

  /** Parse /proc/net/route for default route (dest 00000000) */
  private static String getDefaultRouteIfaceV4() {
    Path p = Path.of("/proc/net/route");
    if (!Files.isRegularFile(p)) return null;
    try (BufferedReader br = Files.newBufferedReader(p)) {
      String line; // skip header
      br.readLine();
      while ((line = br.readLine()) != null) {
        String[] f = line.split("\\t");
        if (f.length < 11) continue;
        String iface = f[0];
        String dest  = f[1];
        String flags = f[3];
        if ("00000000".equals(dest)) {
          int fl = Integer.decode("0x" + flags);
          // RTF_UP (0x1) and RTF_GATEWAY (0x2)
          if ((fl & 0x3) == 0x3 && !isIgnored(iface)) return iface;
        }
      }
    } catch (IOException ignored) {}
    return null;
  }

  /** Crude IPv6 default route detector via `ip -6` proc file (kernel summary) */
  private static String getDefaultRouteIfaceV6() {
    // Fallback: check `/proc/net/ipv6_route` for default ::/0 (all zeroes)
    Path p = Path.of("/proc/net/ipv6_route");
    if (!Files.isRegularFile(p)) return null;
    try (Stream<String> lines = Files.lines(p)) {
      return lines
          .map(String::trim)
          .map(l -> l.split("\\s+"))
          .filter(f -> f.length >= 10)
          .filter(f -> f[0].equals("00000000000000000000000000000000") && f[1].equals("00")) // dest + prefix
          .map(f -> f[9]) // iface
          .filter(iface -> !isIgnored(iface))
          .findFirst().orElse(null);
    } catch (IOException ignored) {}
    return null;
  }
}
