// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/runtime/client_cache.h

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <functional>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "common/status.h"
#include "util/hash_util.hpp"
#include "util/metrics.h"
#include "util/thrift_client.h"

namespace starrocks {

// Helper class which implements the majority of the caching
// functionality without using templates (i.e. pointers to the
// superclass of all ThriftClients and a void* for the key).
//
// The user of this class only sees RPC proxy classes, but we have
// to track the ThriftClient to manipulate the underlying
// transport. To do this, we maintain a map from an opaque 'key'
// pointer type to the client implementation. We actually know the
// type of the pointer (it's the type parameter to ClientCache), but
// we deliberately avoid using it so that this entire class doesn't
// get inlined every time it gets used.
//
// This class is thread-safe.
//
// TODO: shut down clients in the background if they don't get used for a period of time
// TODO: in order to reduce locking overhead when getting/releasing clients,
// add call to hand back pointer to list stored in ClientCache and add separate lock
// to list (or change to lock-free list)
// TODO: reduce locking overhead and by adding per-address client caches, each with its
// own lock.
// TODO: More graceful handling of clients that have failed (maybe better
// handled by a smart-wrapper of the interface object).
// TODO: limits on total number of clients, and clients per-backend
class ClientCacheHelper {
public:
    ~ClientCacheHelper();
    // Callback method which produces a client object when one cannot be
    // found in the cache. Supplied by the ClientCache wrapper.
    typedef std::function<ThriftClientImpl*(const TNetworkAddress& hostport, void** client_key)> client_factory;

    // Return client for specific host/port in 'client'. If a client
    // is not available, the client parameter is set to NULL.
    Status get_client(const TNetworkAddress& hostport, const client_factory& factory_method, void** client_key,
                      int timeout_ms);

    // Close and delete the underlying transport and remove the client from _client_map.
    // Return a new client connecting to the same host/port.
    // Return an error status and set client_key to NULL if a new client cannot
    // created.
    Status reopen_client(const client_factory& factory_method, void** client_key, int timeout_ms);

    // Return a client to the cache, without closing it, and set *client_key to NULL.
    void release_client(void** client_key);

    // Close all connections to a host (e.g., in case of failure) so that on their
    // next use they will have to be Reopen'ed.
    void close_connections(const TNetworkAddress& address);

    std::string debug_string();

    void init_metrics(MetricRegistry* metrics, const std::string& key_prefix);

private:
    template <class T>
    friend class ClientCache;
    // Private constructor so that only ClientCache can instantiate this class.
    ClientCacheHelper() = default;

    explicit ClientCacheHelper(int max_cache_size_per_host) : _max_cache_size_per_host(max_cache_size_per_host) {}

    // Protects all member variables
    // TODO: have more fine-grained locks or use lock-free data structures,
    // this isn't going to scale for a high request rate
    std::mutex _lock;

    // map from (host, port) to list of client keys for that address
    typedef std::unordered_map<TNetworkAddress, std::list<void*> > ClientCacheMap;
    ClientCacheMap _client_cache;

    // Map from client key back to its associated ThriftClientImpl transport
    typedef std::unordered_map<void*, ThriftClientImpl*> ClientMap;
    ClientMap _client_map;

    // MetricRegistry
    bool _metrics_enabled{false};

    // max connections per host in this cache, -1 means unlimited
    int _max_cache_size_per_host{-1};

    // Number of clients 'checked-out' from the cache
    std::unique_ptr<IntGauge> _used_clients;

    // Total clients in the cache, including those in use
    std::unique_ptr<IntGauge> _opened_clients;

    // Create a new client for specific host/port in 'client' and put it in _client_map
    Status _create_client(const TNetworkAddress& hostport, const client_factory& factory_method, void** client_key);
    void _evict_client(void* client_key, ThriftClientImpl* client);
};

template <class T>
class ClientCache;

// A scoped client connection to help manage clients from a client cache.
//
// Example:
//   {
//     StarRocksInternalServiceConnection client(cache, address, &status);
//     try {
//       client->TransmitData(...);
//     } catch (TTransportException& e) {
//       // Retry
//       RETURN_IF_ERROR(client.Reopen());
//       client->TransmitData(...);
//     }
//   }
// ('client' is released back to cache upon destruction.)
template <class T>
class ClientConnection {
public:
    ClientConnection(ClientCache<T>* client_cache, TNetworkAddress address, Status* status)
            : _client_cache(client_cache), _client(nullptr) {
        *status = _client_cache->get_client(address, &_client, 0);

        if (status->ok()) {
            DCHECK(_client != nullptr);
        }
    }

    ClientConnection(ClientCache<T>* client_cache, TNetworkAddress address, int timeout_ms, Status* status)
            : _client_cache(client_cache), _client(nullptr) {
        *status = _client_cache->get_client(address, &_client, timeout_ms);

        if (status->ok()) {
            DCHECK(_client != nullptr);
        }
    }

    // Test only
    ClientConnection() : _client(nullptr) {}

    ClientConnection(const ClientConnection&) = delete;
    void operator=(const ClientConnection&) = delete;

    ~ClientConnection() {
        if (_client != nullptr) {
            _client_cache->release_client(&_client);
        }
    }

    Status reopen(int timeout_ms) { return _client_cache->reopen_client(&_client, timeout_ms); }

    Status reopen() { return _client_cache->reopen_client(&_client, 0); }

    T* operator->() const { return _client; }

    T* get() { return _client; }

private:
    ClientCache<T>* _client_cache;
    T* _client;
};

// Generic cache of Thrift clients for a given service type.
// This class is thread-safe.
template <class T>
class ClientCache {
public:
    typedef ThriftClient<T> Client;

    ClientCache()
            : _client_factory(std::bind<ThriftClientImpl*>(std::mem_fn(&ClientCache::make_client), this,
                                                           std::placeholders::_1, std::placeholders::_2)) {}

    ClientCache(int max_cache_size)
            : _client_cache_helper(max_cache_size),
              _client_factory(std::bind<ThriftClientImpl*>(std::mem_fn(&ClientCache::make_client), this,
                                                           std::placeholders::_1, std::placeholders::_2)) {}

    // Helper method which returns a debug string
    std::string debug_string() { return _client_cache_helper.debug_string(); }

    // Adds metrics for this cache to the supplied MetricRegistry instance. The
    // metrics have keys that are prefixed by the key_prefix argument
    // (which should not end in a period).
    // Must be called before the cache is used, otherwise the metrics might be wrong
    void init_metrics(MetricRegistry* metrics, const std::string& key_prefix) {
        _client_cache_helper.init_metrics(metrics, key_prefix);
    }

    // Close all clients connected to the supplied address, (e.g., in
    // case of failure) so that on their next use they will have to be
    // Reopen'ed.
    void close_connections(const TNetworkAddress& hostport) { return _client_cache_helper.close_connections(hostport); }

private:
    friend class ClientConnection<T>;

    // Most operations in this class are thin wrappers around the
    // equivalent in ClientCacheHelper, which is a non-templated cache
    // to avoid inlining lots of code wherever this cache is used.
    ClientCacheHelper _client_cache_helper;

    // Function pointer, bound to make_client, which produces clients when the cache is empty
    ClientCacheHelper::client_factory _client_factory;

    // Obtains a pointer to a Thrift interface object (of type T),
    // backed by a live transport which is already open. Returns
    // Status::OK() unless there was an error opening the transport.
    Status get_client(const TNetworkAddress& hostport, T** iface, int timeout_ms) {
        return _client_cache_helper.get_client(hostport, _client_factory, reinterpret_cast<void**>(iface), timeout_ms);
    }

    // Close and delete the underlying transport. Return a new client connecting to the
    // same host/port.
    // Return an error status if a new connection cannot be established and *client will be
    // NULL in that case.
    Status reopen_client(T** client, int timeout_ms) {
        return _client_cache_helper.reopen_client(_client_factory, reinterpret_cast<void**>(client), timeout_ms);
    }

    // Return the client to the cache and set *client to NULL.
    void release_client(T** client) { return _client_cache_helper.release_client(reinterpret_cast<void**>(client)); }

    // Factory method to produce a new ThriftClient<T> for the wrapped cache
    ThriftClientImpl* make_client(const TNetworkAddress& hostport, void** client_key) {
        auto* client = new Client(hostport.hostname, hostport.port);
        *client_key = reinterpret_cast<void*>(client->iface());
        return client;
    }
};

// StarRocks backend client cache, used by a backend to send requests
// to any other backend.
class BackendServiceClient;
typedef ClientCache<BackendServiceClient> BackendServiceClientCache;
typedef ClientConnection<BackendServiceClient> BackendServiceConnection;
class FrontendServiceClient;
typedef ClientCache<FrontendServiceClient> FrontendServiceClientCache;
typedef ClientConnection<FrontendServiceClient> FrontendServiceConnection;
class TFileBrokerServiceClient;
typedef ClientCache<TFileBrokerServiceClient> BrokerServiceClientCache;
typedef ClientConnection<TFileBrokerServiceClient> BrokerServiceConnection;

} // namespace starrocks
