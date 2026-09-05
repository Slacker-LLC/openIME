# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

set(BOOST_VERSION 1.89.0)

set(BOOST_TAR "${CMAKE_CURRENT_SOURCE_DIR}/../../../boost-${BOOST_VERSION}.tar.xz")
if(NOT EXISTS "${BOOST_TAR}")
  set(BOOST_TAR "boost-${BOOST_VERSION}.tar.xz")
  if(NOT EXISTS "${BOOST_TAR}")
    message(STATUS "Downloading Boost ${BOOST_VERSION} ......")
    file(
      DOWNLOAD
      "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
      "${BOOST_TAR}"
      EXPECTED_HASH
        SHA256=67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74
      SHOW_PROGRESS)

    message(STATUS "Remove older version Boost")
    file(REMOVE_RECURSE "${CMAKE_SOURCE_DIR}/boost")
  endif()
endif()

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/boost")
  message(STATUS "Extracting Boost ${BOOST_VERSION} ......")
  file(ARCHIVE_EXTRACT INPUT "${BOOST_TAR}" DESTINATION
       ${CMAKE_SOURCE_DIR})
  if(EXISTS "${CMAKE_SOURCE_DIR}/boost-${BOOST_VERSION}")
    file(RENAME "${CMAKE_SOURCE_DIR}/boost-${BOOST_VERSION}" "${CMAKE_SOURCE_DIR}/boost")
  endif()
endif()

set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    range
    regex
    scope_exit
    signals2
    unordered
    utility
    uuid)

add_subdirectory(boost EXCLUDE_FROM_ALL)
