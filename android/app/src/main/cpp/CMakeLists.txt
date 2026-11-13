cmake_minimum_required(VERSION 3.22.1)
project(hsd_surface LANGUAGES C)
add_library(hsd_surface SHARED codec_yuv.c)
target_link_options(hsd_surface PRIVATE "-Wl,-z,max-page-size=16384")
