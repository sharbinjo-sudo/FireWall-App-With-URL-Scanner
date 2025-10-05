cmake_minimum_required(VERSION 3.22.1)
project(nativefirewall)

add_library(nativefirewall SHARED native_firewall.cpp)

find_library(log-lib log)

target_link_libraries(nativefirewall ${log-lib})
