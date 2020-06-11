[consensus]
    ; the ttl for broadcasting pbft message
    ttl=2
    ; min block generation time(ms), the max block generation time is 1000 ms
    min_block_generation_time=500
    ;enable_dynamic_block_size=true
    enable_ttl_optimization=true
    enable_prepare_with_txsHash=true
    ; The following is the relevant configuration of rpbft
    ; set true to enable broadcast prepare request by tree
    broadcast_prepare_by_tree=true
    ; percent of nodes that broadcast prepare status to, must be between 25 and 100
    prepare_status_broadcast_percent=33
    ; max wait time before request missed transactions, ms, must be between 5ms and 1000ms
    max_request_missedTxs_waitTime=100
    ; maximum wait time before requesting a prepare, ms, must be between 10ms and 1000ms
    max_request_prepare_waitTime=100

[storage]
    ; storage db type, rocksdb / mysql / scalable, rocksdb is recommended
    type=rocksdb
    ; set true to turn on binary log
    binary_log=false
    ; scroll_threshold=scroll_threshold_multiple*1000, only for scalable
    scroll_threshold_multiple=2
    ; set fasle to disable CachedStorage
    cached_storage=true
    ; max cache memeory, MB
    max_capacity=32
    max_forward_block=10
    ; only for external, deprecated in v2.3.0
    max_retry=60
    topic=DB
    ; only for mysql
    db_ip=127.0.0.1
    db_port=3306
    db_username=
    db_passwd=
    db_name=
[tx_pool]
    limit=150000
[sync]
    ; max memory size used for block sync, must >= 32MB
    max_block_sync_memory_size=512
    idle_wait_ms=200
    ; send block status by tree-topology, only supported when use pbft
    sync_block_by_tree=true
    ; send transaction by tree-topology, only supported when use pbft
    ; recommend to use when deploy many consensus nodes
    send_txs_by_tree=true
    ; must between 1000 to 3000
    ; only enabled when sync_by_tree is true
    gossip_interval_ms=1000
    gossip_peers_number=3
    ; max number of nodes that broadcast txs status to, recommended less than 5
    txs_max_gossip_peers_num=5