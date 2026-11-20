#include <valhalla/baldr/graphid.h>
#include <valhalla/baldr/openlr.h>
#include <valhalla/baldr/traffictile.h>
#include <valhalla/midgard/sequence.h>

#include <rapidjson/document.h>
#include <rapidjson/istreamwrapper.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <exception>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <unordered_set>
#include <vector>

namespace {

using valhalla::baldr::MAX_CONGESTION_VAL;
using valhalla::baldr::MAX_TRAFFIC_SPEED_KPH;
using valhalla::baldr::GraphId;
using valhalla::baldr::TrafficSpeed;
using valhalla::baldr::TrafficTileHeader;
using valhalla::baldr::UNKNOWN_TRAFFIC_SPEED_RAW;
using valhalla::midgard::tar;

struct Args {
  std::string command;
  std::string traffic_tar;
  std::string graph_id;
  std::string openlr_reference;
  std::string plan_file;
  double speed_kph = std::numeric_limits<double>::quiet_NaN();
  double congestion = std::numeric_limits<double>::quiet_NaN();
  bool closed = false;
  bool incidents = false;
};

struct ParsedGraphId {
  uint32_t level;
  uint32_t tile_id;
  uint32_t edge_id;
};

struct TarMember {
  std::string name;
  std::uint64_t data_offset;
  std::uint64_t size;
};

[[noreturn]] void usage(const std::string& message = "") {
  if (!message.empty()) {
    std::cerr << "error: " << message << "\n\n";
  }
  std::cerr
      << "usage:\n"
      << "  compass-valhalla-traffic-tool inspect --traffic-tar PATH --graph-id LEVEL/TILE/EDGE\n"
      << "  compass-valhalla-traffic-tool set --traffic-tar PATH --graph-id LEVEL/TILE/EDGE "
         "--speed-kph KPH [--congestion 0..1] [--closed] [--incidents]\n"
      << "  compass-valhalla-traffic-tool reset --traffic-tar PATH --graph-id LEVEL/TILE/EDGE\n"
      << "  compass-valhalla-traffic-tool apply-plan --traffic-tar PATH --plan-file JSON\n"
      << "  compass-valhalla-traffic-tool decode-openlr --reference BASE64\n";
  std::exit(message.empty() ? 0 : 2);
}

Args parse_args(int argc, char* argv[]) {
  if (argc < 2) {
    usage();
  }
  Args args;
  args.command = argv[1];
  if (args.command == "--help" || args.command == "-h") {
    usage();
  }
  if (args.command != "inspect" && args.command != "set" && args.command != "reset" &&
      args.command != "apply-plan" && args.command != "decode-openlr") {
    usage("unknown command: " + args.command);
  }
  for (int i = 2; i < argc; ++i) {
    const std::string key = argv[i];
    auto require_value = [&](const std::string& option) -> std::string {
      if (i + 1 >= argc) {
        usage(option + " requires a value");
      }
      return argv[++i];
    };
    if (key == "--traffic-tar") {
      args.traffic_tar = require_value(key);
    } else if (key == "--graph-id") {
      args.graph_id = require_value(key);
    } else if (key == "--reference") {
      args.openlr_reference = require_value(key);
    } else if (key == "--plan-file") {
      args.plan_file = require_value(key);
    } else if (key == "--speed-kph") {
      args.speed_kph = std::stod(require_value(key));
    } else if (key == "--congestion") {
      args.congestion = std::stod(require_value(key));
    } else if (key == "--closed") {
      args.closed = true;
    } else if (key == "--incidents") {
      args.incidents = true;
    } else if (key == "--help" || key == "-h") {
      usage();
    } else {
      usage("unknown option: " + key);
    }
  }
  if (args.command == "decode-openlr") {
    if (args.openlr_reference.empty()) {
      usage("--reference is required");
    }
    return args;
  }
  if (args.traffic_tar.empty()) {
    usage("--traffic-tar is required");
  }
  if (args.command == "apply-plan") {
    if (args.plan_file.empty()) {
      usage("--plan-file is required");
    }
    return args;
  }
  if (args.graph_id.empty()) {
    usage("--graph-id is required");
  }
  if (args.command == "set" && !args.closed && !std::isfinite(args.speed_kph)) {
    usage("set requires --speed-kph unless --closed is present");
  }
  if (std::isfinite(args.congestion) && (args.congestion < 0.0 || args.congestion > 1.0)) {
    usage("--congestion must be between 0 and 1");
  }
  return args;
}

const char* form_of_way_name(valhalla::baldr::OpenLR::LocationReferencePoint::FormOfWay value) {
  using FormOfWay = valhalla::baldr::OpenLR::LocationReferencePoint::FormOfWay;
  switch (value) {
    case FormOfWay::UNDEFINED:
      return "undefined";
    case FormOfWay::MOTORWAY:
      return "motorway";
    case FormOfWay::MULTIPLE_CARRIAGEWAY:
      return "multiple_carriageway";
    case FormOfWay::SINGLE_CARRIAGEWAY:
      return "single_carriageway";
    case FormOfWay::ROUNDABOUT:
      return "roundabout";
    case FormOfWay::TRAFFICSQUARE:
      return "traffic_square";
    case FormOfWay::SLIPROAD:
      return "slip_road";
    case FormOfWay::OTHER:
      return "other";
  }
  return "unknown";
}

const char* orientation_name(valhalla::baldr::OpenLR::Orientation value) {
  using Orientation = valhalla::baldr::OpenLR::Orientation;
  switch (value) {
    case Orientation::NoOrientation:
      return "none";
    case Orientation::FirstLrpTowardsSecond:
      return "first_lrp_towards_second";
    case Orientation::SecondLrpTowardsFirst:
      return "second_lrp_towards_first";
    case Orientation::BothDirections:
      return "both_directions";
  }
  return "unknown";
}

void print_openlr_json(const Args& args) {
  const valhalla::baldr::OpenLR::OpenLr reference(args.openlr_reference, true);
  std::cout << std::fixed << std::setprecision(7)
            << "{\n"
            << "  \"command\": \"decode-openlr\",\n"
            << "  \"reference\": \"" << args.openlr_reference << "\",\n"
            << "  \"canonical_reference\": \"" << reference.toBase64() << "\",\n"
            << "  \"location_type\": \""
            << (reference.isPointAlongLine ? "point_along_line" : "line") << "\",\n"
            << "  \"line_direction\": \"first_lrp_to_last_lrp\",\n"
            << "  \"orientation\": \"" << orientation_name(reference.orientation) << "\",\n"
            << "  \"positive_offset_bucket\": " << static_cast<unsigned>(reference.poff)
            << ",\n"
            << "  \"negative_offset_bucket\": " << static_cast<unsigned>(reference.noff) << ",\n"
            << "  \"encoded_length_meters\": " << reference.getLength() << ",\n"
            << "  \"lrps\": [\n";
  for (std::size_t index = 0; index < reference.lrps.size(); ++index) {
    const auto& lrp = reference.lrps[index];
    std::cout << "    {\"index\": " << index << ", \"longitude\": " << lrp.longitude
              << ", \"latitude\": " << lrp.latitude << ", \"bearing_degrees\": "
              << lrp.bearing << ", \"distance_to_next_meters\": " << lrp.distance
              << ", \"functional_road_class\": " << static_cast<unsigned>(lrp.frc)
              << ", \"lowest_frc_to_next\": " << static_cast<unsigned>(lrp.lfrcnp)
              << ", \"form_of_way\": \"" << form_of_way_name(lrp.fow) << "\"}"
              << (index + 1 == reference.lrps.size() ? "\n" : ",\n");
  }
  std::cout << "  ]\n}\n";
}

std::vector<std::string> split(const std::string& value, char delimiter) {
  std::vector<std::string> parts;
  std::stringstream stream(value);
  std::string part;
  while (std::getline(stream, part, delimiter)) {
    parts.push_back(part);
  }
  return parts;
}

ParsedGraphId parse_graph_id(const std::string& value) {
  const auto parts = split(value, '/');
  if (parts.size() != 3) {
    throw std::runtime_error("graph id must have form LEVEL/TILE/EDGE");
  }
  return ParsedGraphId{
      static_cast<uint32_t>(std::stoul(parts[0])),
      static_cast<uint32_t>(std::stoul(parts[1])),
      static_cast<uint32_t>(std::stoul(parts[2])),
  };
}

bool ends_with(const std::string& value, const std::string& suffix) {
  return value.size() >= suffix.size() &&
         value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

TarMember find_traffic_tile(tar& archive, const ParsedGraphId& graph_id) {
  TarMember found{};
  bool matched = false;
  const auto corrupt_blocks = archive.for_each(
      [&](const std::string& name, const char* data, const std::size_t size) {
        if (!ends_with(name, ".gph") || size < sizeof(TrafficTileHeader)) {
          return true;
        }
        const auto* traffic_header =
            reinterpret_cast<const TrafficTileHeader*>(static_cast<const void*>(data));
        // Valhalla stores the encoded tile-base GraphId in TrafficTileHeader::tile_id,
        // not the human-readable tile number from LEVEL/TILE/EDGE. Decode it with
        // Valhalla's own GraphId implementation so this stays aligned with the pinned
        // routing-engine version.
        const GraphId traffic_tile_id(traffic_header->tile_id);
        if (traffic_tile_id.level() != graph_id.level ||
            traffic_tile_id.tileid() != graph_id.tile_id || traffic_tile_id.id() != 0) {
          return true;
        }
        found = TarMember{
            name,
            static_cast<std::uint64_t>(data - archive.mm.get()),
            static_cast<std::uint64_t>(size),
        };
        matched = true;
        return false;
      });
  if (!matched) {
    throw std::runtime_error("traffic tile not found for graph id (Valhalla tar corrupt blocks: " +
                             std::to_string(corrupt_blocks) + ")");
  }
  return found;
}

std::uint64_t speed_offset(const TarMember& member, const ParsedGraphId& graph_id) {
  return member.data_offset + sizeof(TrafficTileHeader) +
         (static_cast<std::uint64_t>(graph_id.edge_id) * sizeof(TrafficSpeed));
}

TrafficTileHeader read_header(std::fstream& file, const TarMember& member) {
  TrafficTileHeader header{};
  file.seekg(static_cast<std::streamoff>(member.data_offset), std::ios::beg);
  file.read(reinterpret_cast<char*>(&header), sizeof(header));
  if (!file) {
    throw std::runtime_error("failed to read traffic tile header");
  }
  return header;
}

TrafficSpeed read_speed(std::fstream& file, const TarMember& member, const ParsedGraphId& graph_id) {
  TrafficSpeed speed{};
  file.seekg(static_cast<std::streamoff>(speed_offset(member, graph_id)), std::ios::beg);
  file.read(reinterpret_cast<char*>(&speed), sizeof(speed));
  if (!file) {
    throw std::runtime_error("failed to read traffic speed");
  }
  return speed;
}

uint32_t encode_speed(double speed_kph) {
  if (!std::isfinite(speed_kph) || speed_kph <= 0.0) {
    throw std::runtime_error("speed must be positive unless setting an explicit closure");
  }
  if (speed_kph > MAX_TRAFFIC_SPEED_KPH) {
    speed_kph = MAX_TRAFFIC_SPEED_KPH;
  }
  auto encoded = static_cast<uint32_t>(std::llround(speed_kph / 2.0));
  encoded = std::max<uint32_t>(1, encoded);
  encoded = std::min<uint32_t>(UNKNOWN_TRAFFIC_SPEED_RAW - 1, encoded);
  return encoded;
}

uint32_t encode_congestion(double congestion) {
  if (!std::isfinite(congestion)) {
    return valhalla::baldr::UNKNOWN_CONGESTION_VAL;
  }
  return static_cast<uint32_t>(1 + std::llround(congestion * (MAX_CONGESTION_VAL - 1)));
}

TrafficSpeed make_speed(const Args& args) {
  if (args.command == "reset") {
    return TrafficSpeed{};
  }
  if (args.closed) {
    return TrafficSpeed{
        0,
        0,
        0,
        0,
        255,
        0,
        MAX_CONGESTION_VAL,
        0,
        0,
        true,
    };
  }
  const auto speed = encode_speed(args.speed_kph);
  const auto congestion = encode_congestion(args.congestion);
  return TrafficSpeed{
      speed,
      speed,
      speed,
      speed,
      255,
      0,
      congestion,
      0,
      0,
      args.incidents,
  };
}

void write_speed(std::fstream& file,
                 const TarMember& member,
                 const ParsedGraphId& graph_id,
                 const TrafficSpeed& speed) {
  auto header = read_header(file, member);
  if (graph_id.edge_id >= header.directed_edge_count) {
    throw std::runtime_error("edge id exceeds traffic tile directed edge count");
  }
  header.last_update = static_cast<std::uint64_t>(
      std::chrono::system_clock::to_time_t(std::chrono::system_clock::now()));
  file.seekp(static_cast<std::streamoff>(member.data_offset), std::ios::beg);
  file.write(reinterpret_cast<const char*>(&header), sizeof(header));
  file.seekp(static_cast<std::streamoff>(speed_offset(member, graph_id)), std::ios::beg);
  file.write(reinterpret_cast<const char*>(&speed), sizeof(speed));
  file.flush();
  if (!file) {
    throw std::runtime_error("failed to write traffic speed");
  }
}

void write_header_raw(std::fstream& file,
                      const TarMember& member,
                      const TrafficTileHeader& header) {
  file.seekp(static_cast<std::streamoff>(member.data_offset), std::ios::beg);
  file.write(reinterpret_cast<const char*>(&header), sizeof(header));
  if (!file) {
    throw std::runtime_error("failed to restore traffic tile header");
  }
}

void write_speed_raw(std::fstream& file,
                     const TarMember& member,
                     const ParsedGraphId& graph_id,
                     const TrafficSpeed& speed) {
  file.seekp(static_cast<std::streamoff>(speed_offset(member, graph_id)), std::ios::beg);
  file.write(reinterpret_cast<const char*>(&speed), sizeof(speed));
  if (!file) {
    throw std::runtime_error("failed to restore traffic speed");
  }
}

struct PreparedOperation {
  Args args;
  ParsedGraphId graph_id;
  TarMember member;
  TrafficSpeed previous_speed;
  TrafficSpeed next_speed;
};

struct HeaderSnapshot {
  TarMember member;
  TrafficTileHeader header;
};

std::vector<Args> read_plan_operations(const Args& args,
                                       std::size_t& set_count,
                                       std::size_t& reset_count,
                                       bool& require_unknown) {
  std::ifstream input(args.plan_file);
  if (!input.is_open()) {
    throw std::runtime_error("failed to open traffic plan: " + args.plan_file);
  }
  rapidjson::IStreamWrapper wrapper(input);
  rapidjson::Document document;
  document.ParseStream(wrapper);
  if (document.HasParseError() || !document.IsObject()) {
    throw std::runtime_error("traffic plan must be a JSON object");
  }
  if (!document.HasMember("set_updates") || !document["set_updates"].IsArray() ||
      !document.HasMember("reset_graph_ids") || !document["reset_graph_ids"].IsArray()) {
    throw std::runtime_error("traffic plan requires set_updates and reset_graph_ids arrays");
  }
  require_unknown = false;
  if (document.HasMember("require_unknown")) {
    if (!document["require_unknown"].IsBool()) {
      throw std::runtime_error("traffic plan require_unknown must be boolean");
    }
    require_unknown = document["require_unknown"].GetBool();
  }

  std::vector<Args> operations;
  std::unordered_set<std::string> graph_ids;
  const auto& updates = document["set_updates"];
  const auto& resets = document["reset_graph_ids"];
  if (updates.Size() + resets.Size() > 100000) {
    throw std::runtime_error("traffic plan exceeds the operation safety limit");
  }
  for (const auto& update : updates.GetArray()) {
    if (!update.IsObject() || !update.HasMember("graph_id") ||
        !update["graph_id"].IsString()) {
      throw std::runtime_error("traffic set update requires a string graph_id");
    }
    Args operation;
    operation.command = "set";
    operation.traffic_tar = args.traffic_tar;
    operation.graph_id = update["graph_id"].GetString();
    if (!graph_ids.insert(operation.graph_id).second) {
      throw std::runtime_error("traffic plan contains duplicate graph id: " + operation.graph_id);
    }
    if (update.HasMember("closed") && !update["closed"].IsBool()) {
      throw std::runtime_error("traffic closed flag must be boolean");
    }
    if (update.HasMember("has_incidents") && !update["has_incidents"].IsBool()) {
      throw std::runtime_error("traffic incident flag must be boolean");
    }
    operation.closed =
        update.HasMember("closed") && update["closed"].GetBool();
    operation.incidents =
        update.HasMember("has_incidents") && update["has_incidents"].GetBool();
    if (!operation.closed) {
      if (!update.HasMember("speed_kph") || !update["speed_kph"].IsNumber()) {
        throw std::runtime_error("non-closure traffic update requires numeric speed_kph");
      }
      operation.speed_kph = update["speed_kph"].GetDouble();
    }
    if (update.HasMember("congestion") && !update["congestion"].IsNull()) {
      if (!update["congestion"].IsNumber()) {
        throw std::runtime_error("traffic congestion must be numeric or null");
      }
      operation.congestion = update["congestion"].GetDouble();
      if (operation.congestion < 0.0 || operation.congestion > 1.0) {
        throw std::runtime_error("traffic congestion must be between 0 and 1");
      }
    }
    // Validate the encoded representation before touching the archive.
    make_speed(operation);
    operations.push_back(operation);
  }
  for (const auto& reset : resets.GetArray()) {
    if (!reset.IsString()) {
      throw std::runtime_error("traffic reset GraphId must be a string");
    }
    Args operation;
    operation.command = "reset";
    operation.traffic_tar = args.traffic_tar;
    operation.graph_id = reset.GetString();
    if (!graph_ids.insert(operation.graph_id).second) {
      throw std::runtime_error("traffic plan contains duplicate graph id: " + operation.graph_id);
    }
    operations.push_back(operation);
  }
  set_count = updates.Size();
  reset_count = resets.Size();
  return operations;
}

void apply_plan(const Args& args) {
  std::size_t set_count = 0;
  std::size_t reset_count = 0;
  bool require_unknown = false;
  const auto operations =
      read_plan_operations(args, set_count, reset_count, require_unknown);
  tar archive(args.traffic_tar, true);
  std::fstream file(args.traffic_tar, std::ios::in | std::ios::out | std::ios::binary);
  if (!file.is_open()) {
    throw std::runtime_error("failed to open traffic tar: " + args.traffic_tar);
  }

  std::vector<PreparedOperation> prepared;
  std::map<std::uint64_t, HeaderSnapshot> headers;
  prepared.reserve(operations.size());
  for (const auto& operation : operations) {
    const auto graph_id = parse_graph_id(operation.graph_id);
    const auto member = find_traffic_tile(archive, graph_id);
    const auto header = read_header(file, member);
    if (graph_id.edge_id >= header.directed_edge_count) {
      throw std::runtime_error("edge id exceeds traffic tile directed edge count");
    }
    headers.emplace(member.data_offset, HeaderSnapshot{member, header});
    const auto previous_speed = read_speed(file, member, graph_id);
    if (require_unknown && (previous_speed.speed_valid() || previous_speed.closed())) {
      throw std::runtime_error("traffic plan expected unknown prior speed for graph id: " +
                               operation.graph_id);
    }
    prepared.push_back(PreparedOperation{
        operation,
        graph_id,
        member,
        previous_speed,
        make_speed(operation),
    });
  }

  try {
    for (const auto& operation : prepared) {
      write_speed(file, operation.member, operation.graph_id, operation.next_speed);
    }
  } catch (...) {
    const auto original_error = std::current_exception();
    try {
      file.clear();
      for (const auto& operation : prepared) {
        write_speed_raw(file, operation.member, operation.graph_id, operation.previous_speed);
      }
      for (const auto& [offset, snapshot] : headers) {
        static_cast<void>(offset);
        write_header_raw(file, snapshot.member, snapshot.header);
      }
      file.flush();
    } catch (const std::exception& rollback_error) {
      throw std::runtime_error("traffic plan failed and rollback also failed: " +
                               std::string(rollback_error.what()));
    }
    std::rethrow_exception(original_error);
  }
  file.flush();
  if (!file) {
    throw std::runtime_error("failed to flush applied traffic plan");
  }
  std::cout << "{\n"
            << "  \"command\": \"apply-plan\",\n"
            << "  \"traffic_tar\": \"" << args.traffic_tar << "\",\n"
            << "  \"set_count\": " << set_count << ",\n"
            << "  \"reset_count\": " << reset_count << ",\n"
            << "  \"operation_count\": " << prepared.size() << "\n"
            << "}\n";
}

void print_json(const Args& args,
                const ParsedGraphId& graph_id,
                const TarMember& member,
                const TrafficTileHeader& header,
                const TrafficSpeed& speed) {
  std::cout << "{\n"
            << "  \"command\": \"" << args.command << "\",\n"
            << "  \"traffic_tar\": \"" << args.traffic_tar << "\",\n"
            << "  \"graph_id\": \"" << args.graph_id << "\",\n"
            << "  \"level\": " << graph_id.level << ",\n"
            << "  \"tile_id\": " << graph_id.tile_id << ",\n"
            << "  \"edge_id\": " << graph_id.edge_id << ",\n"
            << "  \"member_name\": \"" << member.name << "\",\n"
            << "  \"directed_edge_count\": " << header.directed_edge_count << ",\n"
            << "  \"last_update\": " << header.last_update << ",\n"
            << "  \"traffic_tile_version\": " << header.traffic_tile_version << ",\n"
            << "  \"traffic_speed_size\": " << sizeof(TrafficSpeed) << ",\n"
            << "  \"speed_valid\": " << (speed.speed_valid() ? "true" : "false") << ",\n"
            << "  \"closed\": " << (speed.closed() ? "true" : "false") << ",\n"
            << "  \"overall_speed_kph\": ";
  if (speed.speed_valid()) {
    std::cout << static_cast<int>(speed.get_overall_speed());
  } else {
    std::cout << "null";
  }
  std::cout << ",\n"
            << "  \"congestion_raw\": " << static_cast<int>(speed.congestion1) << ",\n"
            << "  \"has_incidents\": " << (speed.has_incidents ? "true" : "false") << "\n"
            << "}\n";
}

} // namespace

int main(int argc, char* argv[]) {
  try {
    static_assert(sizeof(TrafficTileHeader) == sizeof(uint64_t) * 4);
    static_assert(sizeof(TrafficSpeed) == sizeof(uint64_t));

    const auto args = parse_args(argc, argv);
    if (args.command == "decode-openlr") {
      print_openlr_json(args);
      return 0;
    }
    if (args.command == "apply-plan") {
      apply_plan(args);
      return 0;
    }
    const auto graph_id = parse_graph_id(args.graph_id);
    tar archive(args.traffic_tar, true);
    std::fstream file(args.traffic_tar, std::ios::in | std::ios::out | std::ios::binary);
    if (!file.is_open()) {
      throw std::runtime_error("failed to open traffic tar: " + args.traffic_tar);
    }

    const auto member = find_traffic_tile(archive, graph_id);
    auto header = read_header(file, member);
    if (graph_id.edge_id >= header.directed_edge_count) {
      throw std::runtime_error("edge id exceeds traffic tile directed edge count");
    }

    TrafficSpeed speed = read_speed(file, member, graph_id);
    if (args.command == "set" || args.command == "reset") {
      speed = make_speed(args);
      write_speed(file, member, graph_id, speed);
      header = read_header(file, member);
      speed = read_speed(file, member, graph_id);
    }

    print_json(args, graph_id, member, header, speed);
  } catch (const std::exception& error) {
    std::cerr << "error: " << error.what() << "\n";
    return 1;
  }
  return 0;
}
