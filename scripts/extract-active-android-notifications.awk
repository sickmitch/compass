/^[[:space:]]*Notification List:/ {
    active_list = 1
}

active_list && /^[[:space:]]*mArchive=/ {
    exit
}

active_list {
    print
}
