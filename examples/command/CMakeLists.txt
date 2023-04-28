if (WHISPER_SUPPORT_SDL2)
    # command
    set(TARGET command)
    add_executable(${TARGET} command.cpp)

    include(DefaultTargetOptions)
    find_package(nlohmann_json CONFIG REQUIRED)
    target_link_libraries(main PRIVATE nlohmann_json::nlohmann_json)
    target_link_libraries(${TARGET} PRIVATE common common-sdl whisper ${CMAKE_THREAD_LIBS_INIT})
endif ()
