# Install pods needed to embed Flutter application
require 'json'

def parse_KV_file(file, separator='=')
  file_abs_path = File.expand_path(file)
  if !File.exist?(file_abs_path)
    return [];
  end
  generated = File.read(file_abs_path)
  lines = generated.split("\n")
  result = {}
  lines.each do |line|
    key_value = line.split(separator)
    if key_value.length == 2
      result[key_value[0]] = key_value[1]
    end
  end
  return result
end

def flutter_root
  generated_xcode_build_settings = parse_KV_file(File.join(__dir__, 'Generated.xcconfig'))
  if generated_xcode_build_settings.empty?
    puts "Generated.xcconfig must exist. Make sure `flutter pub get` is executed in the Flutter project root."
    exit
  end
  generated_xcode_build_settings['FLUTTER_ROOT']
end

def relative(path)
  File.join('${PODS_TARGET_SRCROOT}', path)
end

def install_flutter_engine_pod(pod_name, flutter_application_path)
  engine_dir = File.expand_path(File.join(flutter_application_path, '.ios', 'Flutter'))
  framework_name = pod_name == 'Flutter' ? 'Flutter.xcframework' : "#{pod_name}.xcframework"
  pod pod_name, :path => File.join(engine_dir, framework_name)
end

def install_flutter_plugin_pods(flutter_application_path)
  flutter_application_path ||= File.expand_path('..', __dir__)
  manifest_path = File.join(flutter_application_path, '.flutter-plugins-dependencies')
  unless File.exist?(manifest_path)
    puts "No `#{manifest_path}` file found. Make sure `flutter pub get` is executed in the Flutter project root."
    exit
  end

  plugin_pods = JSON.parse(File.read(manifest_path))
  plugin_pods.each do |plugin|
    pod plugin['name'], :path => File.join(flutter_application_path, plugin['path'])
  end
end

def install_all_flutter_pods(flutter_application_path = nil)
  flutter_application_path ||= File.expand_path('..', __dir__)

  install_flutter_engine_pod('Flutter', flutter_application_path)
  install_flutter_engine_pod('FlutterPluginRegistrant', flutter_application_path)
  install_flutter_plugin_pods(flutter_application_path)
end
